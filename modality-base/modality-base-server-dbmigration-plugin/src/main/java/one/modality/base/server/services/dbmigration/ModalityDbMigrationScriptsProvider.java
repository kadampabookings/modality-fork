package one.modality.base.server.services.dbmigration;

import dev.webfx.stack.db.migration.MigrationScript;
import dev.webfx.stack.db.migration.MigrationScriptReader;
import dev.webfx.stack.db.migration.spi.MigrationScriptsProvider;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;

import java.util.List;

/**
 * Contributes the migration scripts bundled in this module's resources to the boot-time migration job
 * (see DbMigrationJob in webfx-stack-db-migration). To add a migration: create the next
 * V000N__short_description.sql file in the scripts/ resource folder and list it in scripts/index.txt
 * (see the rules there). Never edit or remove a shipped script — ship a new version instead.
 *
 * @author Bruno Salmon
 */
public final class ModalityDbMigrationScriptsProvider implements MigrationScriptsProvider {

    @Override
    public Object getDataSourceId() {
        // Same id as the datasource registered by ModalityLocalDataSourceProvider
        return DataSourceModelService.getDefaultDataSourceId();
    }

    @Override
    public List<MigrationScript> getMigrationScripts() {
        // The scripts must live in this class's package for the resources to resolve in both fat-jar and IDE runs
        return MigrationScriptReader.readFromIndex(ModalityDbMigrationScriptsProvider.class, "scripts/index.txt");
    }
}
