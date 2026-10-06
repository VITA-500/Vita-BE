-- English translations share the original FAQ/plan IDs and common attributes.
-- Existing Korean text, vectors, categories, sources and plan attributes stay intact.
CREATE TABLE public.faqs_en (
    faq_id BIGINT PRIMARY KEY REFERENCES public.faqs(id) ON DELETE CASCADE,
    question TEXT NOT NULL CHECK (btrim(question) <> ''),
    answer TEXT NOT NULL CHECK (btrim(answer) <> ''),
    embedding vector(768),
    embedding_model VARCHAR(100),
    embedding_version VARCHAR(30),
    embedded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE public.plans_en (
    plan_id BIGINT PRIMARY KEY REFERENCES public.plans(id) ON DELETE CASCADE,
    name TEXT NOT NULL CHECK (btrim(name) <> ''),
    summary TEXT NOT NULL CHECK (btrim(summary) <> ''),
    description TEXT NOT NULL CHECK (btrim(description) <> ''),
    embedding vector(768),
    embedding_model VARCHAR(100),
    embedding_version VARCHAR(30),
    embedded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
