SELECT 'CATEGORY', category_id, category_name, parent_category_id, parent_category_name,
       sale_count, sale_amount, net_sale_amount
FROM ads_category_sale_m
ORDER BY category_id;

SELECT 'CITY_LEVEL', region, sale_amount, net_sale_amount
FROM ads_region_sale_m
ORDER BY region;

SELECT 'SNAPSHOT', snapshot_id, status, source_id
FROM metric_snapshot
ORDER BY id;

SELECT 'ACTIVE_COUNT', COUNT(*), MIN(snapshot_id), MAX(snapshot_id)
FROM metric_snapshot
WHERE status = 'ACTIVE';
