-- Jace Social: bots written in code and run on Jace Social's servers, and every bot's activity log.
-- Run once in Supabase -> SQL Editor (after 004_community.sql). Safe to re-run.

-- A bot's JavaScript (see lib/codebots.ts): runs in a sandbox when something happens.
alter table profiles add column if not exists bot_code text check (char_length(bot_code) <= 60000);

-- Everything a bot does (and what it was reacting to), so its owner and the admins of the
-- servers it's in can see it. Kept for 14 days.
create table if not exists bot_logs (
  id          bigserial primary key,
  bot         text not null references profiles(uuid) on delete cascade,
  at          timestamptz not null default now(),
  server_id   uuid references servers(id) on delete cascade,
  channel_id  uuid,
  kind        text not null check (kind in ('event', 'message', 'react', 'role', 'delete', 'storage', 'log', 'error', 'command')),
  detail      text not null check (char_length(detail) <= 1000)
);
create index if not exists bot_logs_bot on bot_logs(bot, id desc);
create index if not exists bot_logs_server on bot_logs(server_id, bot, id desc);

alter table bot_logs enable row level security;
grant select, insert, update, delete on bot_logs to service_role;
grant usage, select on all sequences in schema public to service_role;
revoke all on bot_logs from anon, authenticated;
