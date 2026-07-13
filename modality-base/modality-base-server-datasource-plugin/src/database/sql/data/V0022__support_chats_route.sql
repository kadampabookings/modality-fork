SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

INSERT INTO public.operation (id, operation_code, i18n_code, name, label_id, backend, frontend, public, read_only)
VALUES (72, 'RouteToSupportChats', 'SupportChats', 'Support chats', NULL, true, false, true, false);
