-- Section 3B: show a confidence score for scanned/handwritten PDFs, and keep the English
-- translation of non-English PDFs next to the original (the original PDF stays viewable).
ALTER TABLE attachments ADD COLUMN extraction_confidence NUMERIC(4,3);
ALTER TABLE attachments ADD COLUMN translated_text TEXT;
