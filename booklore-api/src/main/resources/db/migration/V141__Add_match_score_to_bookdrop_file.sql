-- Add metadata match score column to bookdrop_file.
-- Populated by BookdropMetadataService when fetched metadata is attached.
-- Range 0-100: title similarity (0-70) + author overlap (0-20) + ISBN match (0-10).
ALTER TABLE bookdrop_file ADD COLUMN match_score INT NULL;
