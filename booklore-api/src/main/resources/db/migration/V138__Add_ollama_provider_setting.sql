UPDATE app_settings
SET val = JSON_SET(val,
    '$.ollama.enabled', false,
    '$.ollama.baseUrl', 'http://ollama:11434',
    '$.ollama.model', 'llama3.1:8b'
)
WHERE name = 'metadata_provider_settings'
  AND JSON_EXTRACT(val, '$.ollama') IS NULL;
