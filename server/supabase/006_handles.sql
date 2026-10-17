-- Jace Social: handles - each account's unique @name, which people choose (and change) themselves.
-- Run once in Supabase -> SQL Editor (after 005_code_bots.sql). Safe to re-run.
--
-- 3-20 characters: lowercase letters, numbers, _ and . (not at the start or end). Unique,
-- ignoring case. Everyone gets one: existing accounts now, new ones when they're created -
-- their Minecraft name, else their Jace name, else their display name, made to fit, with a
-- number added when it's taken. Changing it (PATCH /me {handle}) is allowed once every 7 days.

alter table profiles add column if not exists handle text
  check (handle ~ '^[a-z0-9_]([a-z0-9_.]{1,18}[a-z0-9_])$');
alter table profiles add column if not exists handle_changed_at timestamptz;
create unique index if not exists profiles_handle on profiles (lower(handle));

-- A free handle based on some name ("Steve" -> "steve", taken -> "steve2", ...).
create or replace function jace_free_handle(base text, me text default null) returns text
language plpgsql as $$
declare
  b text := left(regexp_replace(regexp_replace(lower(coalesce(base, '')), '[^a-z0-9_.]', '', 'g'), '^\.+|\.+$', '', 'g'), 17);
  h text;
  n int := 1;
begin
  if b = '' then b := 'player'; elsif char_length(b) < 3 then b := rpad(b, 3, '0'); end if;
  b := regexp_replace(b, '\.+$', '');
  h := b;
  while exists (select 1 from profiles where lower(handle) = h and uuid is distinct from me)
        or h in ('everyone', 'here', 'jace', 'jacesocial', 'admin', 'administrator', 'moderator', 'mod', 'support', 'system', 'staff', 'official', 'help', 'null', 'undefined') loop
    n := n + 1;
    h := left(b, 20 - char_length(n::text)) || n;
  end loop;
  return h;
end $$;

-- New accounts get a handle when they're created.
create or replace function jace_handle_on_insert() returns trigger
language plpgsql as $$
begin
  if new.handle is null then
    new.handle := jace_free_handle(
      case when coalesce(new.mc_linked, true) and not coalesce(new.is_bot, false) then new.name   -- a Minecraft player
           else coalesce(nullif(new.jace_name, ''), nullif(new.display_name, ''), new.name) end,
      new.uuid);
  end if;
  return new;
end $$;
drop trigger if exists profiles_handle_insert on profiles;
create trigger profiles_handle_insert before insert on profiles for each row execute function jace_handle_on_insert();

-- Existing accounts: Minecraft players first (their name is theirs), then Jace-only accounts, then bots.
do $$
declare r record;
begin
  for r in select uuid, name from profiles where handle is null and coalesce(mc_linked, true) and not coalesce(is_bot, false)
           order by signed_up nulls last loop
    update profiles set handle = jace_free_handle(r.name, r.uuid) where uuid = r.uuid;
  end loop;
  for r in select uuid, coalesce(nullif(jace_name, ''), display_name, name) as base from profiles where handle is null
           order by is_bot, signed_up nulls last loop
    update profiles set handle = jace_free_handle(r.base, r.uuid) where uuid = r.uuid;
  end loop;
end $$;

grant execute on function jace_free_handle(text, text) to service_role;
