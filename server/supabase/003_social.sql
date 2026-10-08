-- Jace Social: Jace accounts, profiles, status, group chats and servers.
-- Run once in Supabase -> SQL Editor (after schema.sql and 002_calls.sql). Safe to re-run.

-- ---------------------------------------------------------------------------
-- Accounts: a player can sign in with Minecraft, Jace, or both (linked).
-- profiles.uuid stays the player's id everywhere. Minecraft players keep their
-- Minecraft UUID; Jace-only players get a random 32-hex id, and when they link
-- Minecraft later their account is merged into the Minecraft one.
-- ---------------------------------------------------------------------------
alter table profiles add column if not exists jace_sub       text unique;      -- Jace account id
alter table profiles add column if not exists jace_name      text;             -- Jace username
alter table profiles add column if not exists mc_linked      boolean not null default true;  -- false = Jace-only
-- profile
alter table profiles add column if not exists display_name   text check (char_length(display_name) <= 32);
alter table profiles add column if not exists avatar_url     text check (char_length(avatar_url) <= 500);
alter table profiles add column if not exists bio            text check (char_length(bio) <= 300);
alter table profiles add column if not exists pronouns       text check (char_length(pronouns) <= 24);
alter table profiles add column if not exists accent_color   text check (accent_color ~ '^#[0-9a-fA-F]{6}$');
alter table profiles add column if not exists links          jsonb not null default '[]'::jsonb;  -- [{label, url}]
-- status (like Discord): online / idle / dnd / invisible, plus a custom status line
alter table profiles add column if not exists status         text not null default 'online'
  check (status in ('online', 'idle', 'dnd', 'invisible'));
alter table profiles add column if not exists custom_status  text check (char_length(custom_status) <= 80);
alter table profiles add column if not exists status_emoji   text check (char_length(status_emoji) <= 16);
create index if not exists profiles_jace_name on profiles (lower(jace_name));
create index if not exists profiles_name on profiles (lower(name));

-- Sign in with Jace / link Jace: one row per browser round trip.
create table if not exists oauth_requests (
  state       text primary key,                -- random, sent to Jace and back
  purpose     text not null check (purpose in ('signin', 'link')),
  link_uuid   text references profiles(uuid) on delete cascade,  -- for 'link': who is linking
  poll_hash   text,                            -- desktop apps poll for the result with this key
  return_to   text,                            -- web: where to go afterwards
  result      jsonb,                           -- {token, ...} or {error}
  created_at  timestamptz not null default now()
);

-- ---------------------------------------------------------------------------
-- Group chats and servers share "channels" and "channel_messages".
-- A group chat is a channel with server_id null and its own member list;
-- a server channel belongs to a server, and every server member can see it.
-- ---------------------------------------------------------------------------
create table if not exists servers (
  id          uuid primary key default gen_random_uuid(),
  name        text not null check (char_length(name) between 1 and 48),
  icon_url    text check (char_length(icon_url) <= 500),
  description text check (char_length(description) <= 300),
  owner       text not null references profiles(uuid) on delete cascade,
  invite_code text not null unique,
  created_at  timestamptz not null default now()
);

create table if not exists server_members (
  server_id   uuid not null references servers(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  role        text not null default 'member' check (role in ('owner', 'admin', 'member')),
  joined_at   timestamptz not null default now(),
  primary key (server_id, uuid)
);
create index if not exists server_members_uuid on server_members(uuid);

create table if not exists channels (
  id          uuid primary key default gen_random_uuid(),
  server_id   uuid references servers(id) on delete cascade,   -- null = group chat
  name        text not null check (char_length(name) between 1 and 48),
  topic       text check (char_length(topic) <= 200),
  icon_url    text check (char_length(icon_url) <= 500),       -- group chats
  owner       text references profiles(uuid) on delete set null,
  position    int not null default 0,
  created_at  timestamptz not null default now()
);
create index if not exists channels_server on channels(server_id, position);

create table if not exists channel_members (           -- group chats only
  channel_id  uuid not null references channels(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  joined_at   timestamptz not null default now(),
  primary key (channel_id, uuid)
);
create index if not exists channel_members_uuid on channel_members(uuid);

create table if not exists channel_messages (
  id          bigint generated always as identity primary key,
  channel_id  uuid not null references channels(id) on delete cascade,
  sender      text references profiles(uuid) on delete set null,
  body        text not null check (char_length(body) between 1 and 2000),
  kind        text not null default 'text' check (kind in ('text', 'system')),
  created_at  timestamptz not null default now(),
  edited_at   timestamptz
);
create index if not exists channel_messages_channel on channel_messages(channel_id, id);

create table if not exists channel_reads (              -- unread counts
  channel_id  uuid not null references channels(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  last_read   bigint not null default 0,
  primary key (channel_id, uuid)
);

alter table oauth_requests   enable row level security;
alter table servers          enable row level security;
alter table server_members   enable row level security;
alter table channels         enable row level security;
alter table channel_members  enable row level security;
alter table channel_messages enable row level security;
alter table channel_reads    enable row level security;
grant select, insert, update, delete on oauth_requests, servers, server_members, channels, channel_members,
  channel_messages, channel_reads to service_role;
grant usage, select on all sequences in schema public to service_role;
revoke all on oauth_requests, servers, server_members, channels, channel_members, channel_messages, channel_reads
  from anon, authenticated;

-- Profile pictures and server/group icons (public files, uploaded through the server).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 2097152, array['image/png', 'image/jpeg', 'image/gif', 'image/webp'])
on conflict (id) do nothing;

-- Merge one player into another (linking a Jace-only account to a Minecraft one,
-- or the other way round). Everything of "src" moves to "dst", then "src" is deleted.
-- One function = one transaction, so a failed merge changes nothing.
create or replace function merge_profiles(src text, dst text) returns void
language plpgsql security definer set search_path = public as $$
declare
  s profiles%rowtype;
begin
  if src = dst then return; end if;
  select * into s from profiles where uuid = src;
  if not found then return; end if;
  update profiles set jace_sub = null where uuid = src;   -- free the unique value for dst
  -- friendships: drop the pair between them, and duplicates dst already has
  delete from friendships where (requester = src and addressee = dst) or (requester = dst and addressee = src);
  delete from friendships f where f.requester = src and exists (
    select 1 from friendships g where (g.requester = dst and g.addressee = f.addressee) or (g.addressee = dst and g.requester = f.addressee));
  delete from friendships f where f.addressee = src and exists (
    select 1 from friendships g where (g.requester = dst and g.addressee = f.requester) or (g.addressee = dst and g.requester = f.requester));
  update friendships set requester = dst where requester = src;
  update friendships set addressee = dst where addressee = src;
  -- direct messages and calls
  update messages set sender = dst where sender = src;
  update messages set recipient = dst where recipient = src;
  delete from call_signals where sender = src or recipient = src;
  -- servers, group chats, channel messages
  insert into server_members (server_id, uuid, role, joined_at)
    select server_id, dst, role, joined_at from server_members where uuid = src on conflict do nothing;
  delete from server_members where uuid = src;
  insert into channel_members (channel_id, uuid, joined_at)
    select channel_id, dst, joined_at from channel_members where uuid = src on conflict do nothing;
  delete from channel_members where uuid = src;
  insert into channel_reads (channel_id, uuid, last_read)
    select channel_id, dst, last_read from channel_reads where uuid = src on conflict do nothing;
  delete from channel_reads where uuid = src;
  update servers set owner = dst where owner = src;
  update channels set owner = dst where owner = src;
  update channel_messages set sender = dst where sender = src;
  update sessions set uuid = dst where uuid = src;
  -- keep dst's profile, filling gaps from src
  update profiles d set
    jace_sub      = coalesce(d.jace_sub, s.jace_sub),
    jace_name     = coalesce(d.jace_name, s.jace_name),
    display_name  = coalesce(d.display_name, s.display_name),
    avatar_url    = coalesce(d.avatar_url, s.avatar_url),
    bio           = coalesce(d.bio, s.bio),
    pronouns      = coalesce(d.pronouns, s.pronouns),
    accent_color  = coalesce(d.accent_color, s.accent_color),
    links         = case when d.links = '[]'::jsonb then s.links else d.links end,
    custom_status = coalesce(d.custom_status, s.custom_status),
    signed_up     = coalesce(d.signed_up, s.signed_up)
  where d.uuid = dst;
  delete from profiles where uuid = src;
end $$;
revoke all on function merge_profiles(text, text) from public, anon, authenticated;
grant execute on function merge_profiles(text, text) to service_role;

notify pgrst, 'reload schema';
