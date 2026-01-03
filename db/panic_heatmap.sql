create or replace function public.get_panic_heatmap(
    lat double precision,
    lng double precision,
    radius_km double precision,
    limit_count integer default 500
)
returns table (
    lat double precision,
    lng double precision,
    weight integer
)
language sql
stable
as $$
    with base as (
        select
            st_y(location::geometry) as lat,
            st_x(location::geometry) as lng,
            (case when is_active then 3 else 0 end) +
            (case
                when started_at >= now() - interval '1 hour' then 2
                when started_at >= now() - interval '24 hours' then 1
                else 0
            end) as weight,
            started_at
        from public.panic_events
        where started_at >= now() - interval '24 hours'
          and st_dwithin(
              location,
              st_setsrid(st_makepoint(lng, lat), 4326)::geography,
              radius_km * 1000.0
          )
    )
    select lat, lng, weight
    from base
    where weight > 0
    order by started_at desc
    limit limit_count;
$$;
