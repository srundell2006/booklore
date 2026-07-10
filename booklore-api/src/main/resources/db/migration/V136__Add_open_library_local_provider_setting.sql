UPDATE app_settings
SET val = JSON_SET(val, '$.openLibraryLocal.enabled', false)
WHERE name = 'metadata_provider_settings'
  AND JSON_EXTRACT(val, '$.openLibraryLocal') IS NULL;
