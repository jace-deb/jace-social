-- Jace Social: roles and permissions, replies, reactions, mentions, files, link embeds,
-- moderation, custom invite links, onboarding, voice channels, screen sharing and bots.
-- Run once in Supabase -> SQL Editor (after 003_social.sql). Safe to re-run.

-- ---------------------------------------------------------------------------
-- Permissions are bits (like Discord's), stored as bigint:
--   1 view channels        2 send messages      4 attach files       8 add reactions
--   16 mention @everyone   32 manage messages   64 manage channels   128 manage roles
--   256 manage server      512 kick members     1024 ban members     2048 create invites
--   4096 change nickname   8192 manage nicknames 16384 connect (voice) 32768 speak
--   65536 share screen     131072 time out members 262144 manage bots  1073741824 administrator
-- The server owner and the administrator bit can do everything.
-- ---------------------------------------------------------------------------
create table if not exists server_roles (
  id          uuid primary key default gen_random_uuid(),
  server_id   uuid not null references servers(id) on delete cascade,
  name        text not null check (char_length(name) between 1 and 32),
  color       text check (color ~ '^#[0-9a-fA-F]{6}$'),
  icon        text check (char_length(icon) <= 16),              -- an emoji
  position    int not null default 0,                            -- higher = more important
  permissions bigint not null default 0,
  hoist       boolean not null default false,                    -- listed separately in the member list
  mentionable boolean not null default false,
  is_default  boolean not null default false,                    -- @everyone (one per server)
  created_at  timestamptz not null default now()
);
create index if not exists server_roles_server on server_roles(server_id, position);
create unique index if not exists server_roles_default on server_roles(server_id) where is_default;

create table if not exists server_member_roles (
  server_id   uuid not null references servers(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  role_id     uuid not null references server_roles(id) on delete cascade,
  primary key (server_id, uuid, role_id)
);
create index if not exists server_member_roles_role on server_member_roles(role_id);

alter table server_members add column if not exists nickname      text check (char_length(nickname) <= 32);
alter table server_members add column if not exists timeout_until timestamptz;            -- can't talk until then
alter table server_members add column if not exists onboarded     boolean not null default false;

create table if not exists server_bans (
  server_id   uuid not null references servers(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  reason      text check (char_length(reason) <= 200),
  banned_by   text references profiles(uuid) on delete set null,
  created_at  timestamptz not null default now(),
  primary key (server_id, uuid)
);

-- Channels: text, voice, announcement and categories (which hold other channels).
alter table channels add column if not exists kind       text not null default 'text'
  check (kind in ('text', 'voice', 'announcement', 'category'));
alter table channels add column if not exists parent_id  uuid references channels(id) on delete set null;
alter table channels add column if not exists slowmode   int not null default 0 check (slowmode between 0 and 21600);  -- seconds
alter table channels add column if not exists user_limit int not null default 0 check (user_limit between 0 and 25); -- voice

-- Per-channel permission overrides for a role or one member (deny is applied before allow).
create table if not exists channel_overrides (
  channel_id  uuid not null references channels(id) on delete cascade,
  target_type text not null check (target_type in ('role', 'member')),
  target_id   text not null,                                     -- role id or player uuid
  allow       bigint not null default 0,
  deny        bigint not null default 0,
  primary key (channel_id, target_type, target_id)
);

-- Servers: a custom invite link (jace-social.vercel.app/<vanity>), look, welcome and onboarding.
alter table servers add column if not exists vanity          text unique check (vanity ~ '^[a-z0-9-]{3,32}$');
alter table servers add column if not exists banner_url      text check (char_length(banner_url) <= 500);
alter table servers add column if not exists accent_color    text check (accent_color ~ '^#[0-9a-fA-F]{6}$');
alter table servers add column if not exists rules           text check (char_length(rules) <= 2000);
alter table servers add column if not exists welcome         jsonb not null default '{}'::jsonb;  -- {message, channels: [id]}
alter table servers add column if not exists onboarding      jsonb not null default '[]'::jsonb;  -- [{question, options: [{label, emoji, role_ids}]}]
alter table servers add column if not exists system_channel  uuid references channels(id) on delete set null;  -- "X joined" messages
alter table servers add column if not exists invites_paused  boolean not null default false;

-- ---------------------------------------------------------------------------
-- Messages: replies, files, link previews, mentions, pins. Group/server messages
-- (channel_messages) and direct messages (messages) both get replies, files,
-- previews and reactions; mentions and pins are for group chats and servers.
-- ---------------------------------------------------------------------------
alter table channel_messages add column if not exists reply_to        bigint references channel_messages(id) on delete set null;
alter table channel_messages add column if not exists attachments     jsonb not null default '[]'::jsonb;  -- [{url, name, type, size, width?, height?}]
alter table channel_messages add column if not exists embeds          jsonb not null default '[]'::jsonb;  -- [{url, title, description, image, site}]
alter table channel_messages add column if not exists mentions        text[] not null default '{}';         -- player ids
alter table channel_messages add column if not exists mention_roles   uuid[] not null default '{}';
alter table channel_messages add column if not exists mention_everyone boolean not null default false;
alter table channel_messages add column if not exists pinned_at       timestamptz;
alter table channel_messages add column if not exists pinned_by       text references profiles(uuid) on delete set null;
alter table channel_messages add column if not exists components      jsonb not null default '[]'::jsonb;  -- bot buttons
alter table channel_messages drop constraint if exists channel_messages_body_check;
alter table channel_messages add constraint channel_messages_body_check check (char_length(body) <= 4000);  -- files can come without text
create index if not exists channel_messages_pinned on channel_messages(channel_id) where pinned_at is not null;

alter table messages add column if not exists reply_to    bigint references messages(id) on delete set null;
alter table messages add column if not exists attachments jsonb not null default '[]'::jsonb;
alter table messages add column if not exists embeds      jsonb not null default '[]'::jsonb;
alter table messages add column if not exists edited_at   timestamptz;
alter table messages drop constraint if exists messages_body_check;
alter table messages add constraint messages_body_check check (char_length(body) <= 2000);

-- Reactions on both kinds of messages: scope 'c' = channel_messages, 'd' = direct messages.
create table if not exists message_reactions (
  scope       text not null check (scope in ('c', 'd')),
  message_id  bigint not null,
  uuid        text not null references profiles(uuid) on delete cascade,
  emoji       text not null check (char_length(emoji) between 1 and 32),
  created_at  timestamptz not null default now(),
  primary key (scope, message_id, uuid, emoji)
);
create index if not exists message_reactions_message on message_reactions(scope, message_id);

-- ---------------------------------------------------------------------------
-- Voice channels: who's in which room, and the connection setup between them
-- (offers/answers; audio and screens go directly between players, a small mesh).
-- ---------------------------------------------------------------------------
create table if not exists voice_states (
  channel_id  uuid not null references channels(id) on delete cascade,
  uuid        text not null references profiles(uuid) on delete cascade,
  muted       boolean not null default false,
  deafened    boolean not null default false,
  streaming   boolean not null default false,                   -- sharing their screen
  joined_at   timestamptz not null default now(),
  last_seen   timestamptz not null default now(),               -- heartbeat; stale rows are dropped
  primary key (channel_id, uuid)
);
create unique index if not exists voice_states_one_room on voice_states(uuid);

create table if not exists voice_signals (
  id          bigint generated always as identity primary key,
  channel_id  uuid not null references channels(id) on delete cascade,
  sender      text not null references profiles(uuid) on delete cascade,
  recipient   text not null references profiles(uuid) on delete cascade,
  kind        text not null check (kind in ('offer', 'answer')),
  sdp         text not null check (char_length(sdp) <= 30000),
  created_at  timestamptz not null default now()
);
create index if not exists voice_signals_recipient on voice_signals(recipient, id);

-- Direct calls can now carry a screen share too (renegotiated with a new offer/answer).
alter table call_signals drop constraint if exists call_signals_sdp_check;
alter table call_signals add constraint call_signals_sdp_check check (char_length(sdp) <= 30000);
alter table call_signals drop constraint if exists call_signals_kind_check;
alter table call_signals add constraint call_signals_kind_check check (kind in ('offer', 'answer', 'hangup', 'renegotiate', 'reanswer'));

-- ---------------------------------------------------------------------------
-- Bots are accounts too (profiles.is_bot) with an owner. They act through the same API
-- with a bot token, their owner can switch to them in the app, and a bot can be made
-- with blocks (program) that this server runs when something happens.
-- ---------------------------------------------------------------------------
alter table profiles add column if not exists is_bot          boolean not null default false;
alter table profiles add column if not exists bot_owner       text references profiles(uuid) on delete cascade;
alter table profiles add column if not exists bot_public      boolean not null default false;   -- anyone can add it to their server
alter table profiles add column if not exists bot_commands    jsonb not null default '[]'::jsonb;  -- [{name, description, options}]
alter table profiles add column if not exists bot_program     jsonb;                              -- block program (see lib/blocks.ts)
alter table profiles add column if not exists bot_enabled     boolean not null default true;
alter table profiles add column if not exists banner_url      text check (char_length(banner_url) <= 500);
alter table profiles add column if not exists settings        jsonb not null default '{}'::jsonb; -- private: theme, trusted links...
alter table profiles add column if not exists onboarded_at    timestamptz;
create index if not exists profiles_bot_owner on profiles(bot_owner) where is_bot;

alter table sessions add column if not exists kind text not null default 'user' check (kind in ('user', 'bot'));

-- Block bots' own variables (per bot and server), e.g. counters.
create table if not exists bot_storage (
  bot         text not null references profiles(uuid) on delete cascade,
  scope       text not null,                                    -- server id, or 'global'
  key         text not null check (char_length(key) <= 64),
  value       jsonb,
  primary key (bot, scope, key)
);

alter table server_roles        enable row level security;
alter table server_member_roles enable row level security;
alter table server_bans         enable row level security;
alter table channel_overrides   enable row level security;
alter table message_reactions   enable row level security;
alter table voice_states        enable row level security;
alter table voice_signals       enable row level security;
alter table bot_storage         enable row level security;
grant select, insert, update, delete on server_roles, server_member_roles, server_bans, channel_overrides,
  message_reactions, voice_states, voice_signals, bot_storage to service_role;
grant usage, select on all sequences in schema public to service_role;
revoke all on server_roles, server_member_roles, server_bans, channel_overrides, message_reactions,
  voice_states, voice_signals, bot_storage from anon, authenticated;

-- Files in messages (public links, uploaded through the server).
insert into storage.buckets (id, name, public, file_size_limit)
values ('attachments', 'attachments', true, 10485760)
on conflict (id) do nothing;

-- ---------------------------------------------------------------------------
-- Existing servers: an @everyone role with the normal member permissions, and an
-- Admin role (administrator) for everyone who was an admin.
-- ---------------------------------------------------------------------------
insert into server_roles (server_id, name, position, permissions, is_default)
select s.id, '@everyone', 0, 1 | 2 | 4 | 8 | 2048 | 4096 | 16384 | 32768 | 65536, true
from servers s
where not exists (select 1 from server_roles r where r.server_id = s.id and r.is_default);

insert into server_roles (server_id, name, color, position, permissions, hoist)
select s.id, 'Admin', '#3ddc84', 1, 1073741824, true
from servers s
where exists (select 1 from server_members m where m.server_id = s.id and m.role = 'admin')
  and not exists (select 1 from server_roles r where r.server_id = s.id and r.name = 'Admin');

insert into server_member_roles (server_id, uuid, role_id)
select m.server_id, m.uuid, r.id
from server_members m join server_roles r on r.server_id = m.server_id and r.name = 'Admin' and not r.is_default
where m.role = 'admin'
on conflict do nothing;

update servers s set system_channel = (select c.id from channels c where c.server_id = s.id order by c.position, c.created_at limit 1)
where system_channel is null;

-- Merging accounts (merge_profiles in 003) now also moves roles, reactions, voice and bots.
create or replace function merge_profiles_extra(src text, dst text) returns void
language plpgsql security definer set search_path = public as $$
begin
  insert into server_member_roles (server_id, uuid, role_id)
    select server_id, dst, role_id from server_member_roles where uuid = src on conflict do nothing;
  delete from server_member_roles where uuid = src;
  insert into message_reactions (scope, message_id, uuid, emoji, created_at)
    select scope, message_id, dst, emoji, created_at from message_reactions where uuid = src on conflict do nothing;
  delete from message_reactions where uuid = src;
  delete from voice_states where uuid = src;
  delete from voice_signals where sender = src or recipient = src;
  update server_bans set banned_by = dst where banned_by = src;
  update channel_messages set pinned_by = dst where pinned_by = src;
  update profiles set bot_owner = dst where bot_owner = src;
  update profiles d set settings = s.settings || d.settings, onboarded_at = coalesce(d.onboarded_at, s.onboarded_at)
    from profiles s where d.uuid = dst and s.uuid = src;
end $$;
revoke all on function merge_profiles_extra(text, text) from public, anon, authenticated;
grant execute on function merge_profiles_extra(text, text) to service_role;

notify pgrst, 'reload schema';
