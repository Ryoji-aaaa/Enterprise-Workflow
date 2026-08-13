ALTER TABLE expense_application_attachments
    ADD CONSTRAINT uk_expense_attachment_id_application
    UNIQUE (id, expense_application_id);

ALTER TABLE expense_application_auto_entry_contexts
    ADD CONSTRAINT fk_expense_auto_entry_source_attachment_application
    FOREIGN KEY (source_attachment_id, expense_application_id)
    REFERENCES expense_application_attachments (id, expense_application_id);
