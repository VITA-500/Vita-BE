-- V6__rename_enum_values_to_uppercase.sql

ALTER TYPE chat_message_role RENAME VALUE 'user' TO 'USER';
ALTER TYPE chat_message_role RENAME VALUE 'assistant' TO 'ASSISTANT';

ALTER TYPE feedback_type RENAME VALUE 'like' TO 'LIKE';
ALTER TYPE feedback_type RENAME VALUE 'dislike' TO 'DISLIKE';