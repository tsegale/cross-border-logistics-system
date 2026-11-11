-- Identity (ADRs 0016-0020): accounts and FX quotes owned by a customer organisation, idempotency keys scoped to the
-- caller, and every money movement and hold recording who made it.

-- Ownership (ADR 0018). The organisation is the Keycloak organisation id. holder_reference was free text that
-- proved nothing; accounts opened before V7 belong to the "unassigned" organisation, which no customer token can
-- name, so only operators and auditors see them until an operator assigns them.
ALTER TABLE accounts ADD COLUMN owner_organization_id UUID;
UPDATE accounts SET owner_organization_id = '00000000-0000-0000-0000-000000000000' WHERE account_type = 'CUSTOMER';
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_owner
    CHECK ((account_type = 'CUSTOMER') = (owner_organization_id IS NOT NULL));
ALTER TABLE accounts DROP COLUMN holder_reference;
CREATE INDEX idx_accounts_owner ON accounts (owner_organization_id) WHERE owner_organization_id IS NOT NULL;

-- A quote is used by the organisation that asked for it. NULL: made by an operator, or before V7.
ALTER TABLE fx_quotes ADD COLUMN owner_organization_id UUID;

-- Who did it (ADR 0020). NULL only on rows written before V7; the triggers below refuse a new row without a client.
ALTER TABLE journal_entries
    ADD COLUMN actor_subject         VARCHAR(64),
    ADD COLUMN actor_client_id       VARCHAR(64),
    ADD COLUMN actor_organization_id UUID,
    ADD COLUMN request_id            VARCHAR(64);
ALTER TABLE holds
    ADD COLUMN actor_subject                VARCHAR(64),
    ADD COLUMN actor_client_id              VARCHAR(64),
    ADD COLUMN request_id                   VARCHAR(64),
    -- logistics-service acts for a customer: the organisation that booked, and the person, as logistics vouches.
    ADD COLUMN on_behalf_of_organization_id UUID,
    ADD COLUMN on_behalf_of_subject         VARCHAR(64);

CREATE OR REPLACE FUNCTION require_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.actor_client_id IS NULL OR NEW.actor_subject IS NULL THEN
        RAISE EXCEPTION '% row % has no actor: every money movement records who made it', TG_TABLE_NAME, NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entries_require_actor
    BEFORE INSERT ON journal_entries
    FOR EACH ROW
    EXECUTE FUNCTION require_actor();
CREATE TRIGGER trg_holds_require_actor
    BEFORE INSERT ON holds
    FOR EACH ROW
    EXECUTE FUNCTION require_actor();

-- Idempotency keys scoped to the caller (ADR 0020): client id plus organisation or subject. Keys stored before V7
-- were made without an identity and keep the caller "legacy", which no token produces.
ALTER TABLE idempotency_keys ADD COLUMN caller VARCHAR(200) NOT NULL DEFAULT 'legacy';
ALTER TABLE idempotency_keys ALTER COLUMN caller DROP DEFAULT;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (scope, caller, idempotency_key);
