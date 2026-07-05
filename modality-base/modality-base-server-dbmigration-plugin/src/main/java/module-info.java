// File managed by WebFX (DO NOT EDIT MANUALLY)

/**
 * 
        Contributes Modality's bundled DB migration scripts, applied automatically at server boot by
        webfx-stack-db-migration (all pending scripts in a single transaction, gating /health readiness).
        The scripts live in this module's resources, next to an index.txt listing them in version order.
    
 */
module modality.base.server.dbmigration.plugin {

    // Direct dependencies modules
    requires webfx.stack.db.migration;
    requires webfx.stack.orm.datasourcemodel.service;

    // Exported packages
    exports one.modality.base.server.services.dbmigration;

    // Resources packages
    opens one.modality.base.server.services.dbmigration.scripts;

    // Provided services
    provides dev.webfx.stack.db.migration.spi.MigrationScriptsProvider with one.modality.base.server.services.dbmigration.ModalityDbMigrationScriptsProvider;

}