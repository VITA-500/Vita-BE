-- V11__add_error_message_to_chat_messages.sql

ALTER TABLE chat_messages
    ADD COLUMN error_message TEXT;