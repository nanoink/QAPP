create extension if not exists "pgcrypto";

create table if not exists public.panic_events (
    id uuid primary key default gen_random_uuid(),
    driver_id uuid not null,
    location geography(point, 4326) not null,
    is_active boolean not null default true,
    started_at timestamp with time zone not null default now(),
    ended_at timestamp with time zone
);

create index if not exists idx_panic_events_location
    on public.panic_events using gist (location);

create index if not exists idx_panic_events_driver_active
    on public.panic_events (driver_id, is_active);
