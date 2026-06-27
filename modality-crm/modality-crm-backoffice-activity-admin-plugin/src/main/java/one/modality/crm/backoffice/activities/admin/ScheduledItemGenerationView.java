package one.modality.crm.backoffice.activities.admin;

import dev.webfx.extras.util.dialog.DialogCallback;
import dev.webfx.extras.util.dialog.DialogUtil;
import dev.webfx.platform.console.Console;
import dev.webfx.stack.db.submit.SubmitArgument;
import dev.webfx.stack.db.submit.SubmitService;
import dev.webfx.stack.orm.datasourcemodel.service.DataSourceModelService;
import dev.webfx.stack.orm.entity.EntityId;
import dev.webfx.stack.orm.entity.EntityStore;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import one.modality.base.client.mainframe.fx.FXMainFrameDialogArea;
import one.modality.base.shared.entities.*;
import one.modality.crm.backoffice.organization.fx.FXOrganizationId;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * View for generating ScheduledItems (Kitchen and Accommodation) and viewing
 * existing data status.
 */
public class ScheduledItemGenerationView {

    private final BorderPane container = new BorderPane();
    private final DatePicker fromDatePicker = new DatePicker();
    private final DatePicker toDatePicker = new DatePicker();
    private final Button generateKitchenButton = new Button("Generate Kitchen");
    private final Button generateAccommodationButton = new Button("Generate Accommodation");
    private final VBox statusPane = new VBox(10);
    private final ProgressBar progressBar = new ProgressBar(0);
    private final javafx.scene.control.Label statusLabel = new Label();
    private final Label organizationNameLabel = new Label();
    private final GridPane timelinesGrid = new GridPane();
    private final HBox dangerMessageBox = new HBox(10);
    private final Label dangerMessageLabel = new Label();
    private Site globalSite;
    private List<Timeline> availableTimelines = new ArrayList<>();
    private List<Resource> availableResources = new ArrayList<>();
    private Map<Item, List<Resource>> accommodationItemsWithResources = new LinkedHashMap<>();
    private final VBox resourcesDisplayBox = new VBox(5);

    public ScheduledItemGenerationView() {
        initUi();
    }

    public Node getView() {
        return container;
    }

    public void setActive(boolean active) {
        if (active) {
            loadOrganizationInfo();
            refreshStatus();
        }
    }

    private void initUi() {
        fromDatePicker.setValue(LocalDate.now());
        toDatePicker.setValue(LocalDate.now().plusYears(1));

        Button kitchenButton = new Button("Generate Kitchen (Meals)");
        kitchenButton.setOnAction(e -> generateSchedule("meals"));

        Button accommodationButton = new Button("Generate Accommodation");
        accommodationButton.setOnAction(e -> generateSchedule("acco"));

        HBox controls = new HBox(10, new Label("From:"), fromDatePicker, new Label("To:"), toDatePicker, kitchenButton, accommodationButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(10));

        statusPane.setPadding(new Insets(10));

        organizationNameLabel.setPadding(new Insets(10));

        // Danger message box (hidden by default)
        Label warningIcon = new Label("⚠");
        warningIcon.setStyle("-fx-font-size: 16px;");
        dangerMessageLabel.setWrapText(true);
        dangerMessageBox.getChildren().addAll(warningIcon, dangerMessageLabel);
        dangerMessageBox.setAlignment(Pos.CENTER_LEFT);
        dangerMessageBox.setPadding(new Insets(12, 16, 12, 16));
        dangerMessageBox.setStyle("-fx-background-color: #f8d7da; -fx-border-color: #f5c6cb; -fx-border-radius: 4; -fx-background-radius: 4;");
        dangerMessageLabel.setStyle("-fx-text-fill: #721c24;");
        dangerMessageBox.setVisible(false);
        dangerMessageBox.setManaged(false);

        // Timelines grid
        timelinesGrid.setHgap(20);
        timelinesGrid.setVgap(5);
        timelinesGrid.setPadding(new Insets(10));

        // Resources display box
        resourcesDisplayBox.setPadding(new Insets(10));

        // Progress
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setVisible(false);
        statusLabel.setVisible(false);

        HBox statusRow = new HBox(15, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox allContent = new VBox(10, organizationNameLabel, dangerMessageBox, timelinesGrid, resourcesDisplayBox, controls, progressBar, statusRow, statusPane);
        allContent.setPadding(new Insets(10));

        ScrollPane scrollPane = new ScrollPane(allContent);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);

        container.setCenter(scrollPane);
    }

    private void loadOrganizationInfo() {
        EntityId organizationId = FXOrganizationId.getOrganizationId();
        if (organizationId == null) {
            Console.log("loadOrganizationInfo: No organization selected.");
            return;
        }

        Console.log("loadOrganizationInfo: Fetching info for organization " + organizationId);

        EntityStore entityStore = EntityStore.create();
        entityStore.executeQuery("select name, globalSite, globalSite.name from Organization where id=$1", organizationId)
                .onFailure(e -> Console.error("Error fetching organization info", e))
                .onSuccess(result -> {
                    if (!result.isEmpty()) {
                        Organization org = (Organization) result.get(0);
                        String name = org.getName();
                        globalSite = org.getGlobalSite();
                        Console.log("loadOrganizationInfo: Organization: " + name + ", Global Site: "
                                + (globalSite != null ? globalSite.getName() : "null"));
                        Platform.runLater(() -> {
                            organizationNameLabel.setText("Generating for Organization: " + name);
                            organizationNameLabel
                                    .setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
                        });

                        // Fetch timelines for the global site
                        if (globalSite != null) {
                            fetchTimelinesForGlobalSite(entityStore);
                        } else {
                            Platform.runLater(() -> {
                                availableTimelines.clear();
                                availableResources.clear();
                                showDangerMessage("No global site configured for this organization. Cannot generate scheduled items.");
                                timelinesGrid.getChildren().clear();
                                resourcesDisplayBox.getChildren().clear();
                            });
                        }
                    } else {
                        Console.log("loadOrganizationInfo: Organization not found.");
                    }
                });
    }

    private void fetchTimelinesForGlobalSite(EntityStore entityStore) {
        Console.log("fetchTimelinesForGlobalSite: Fetching timelines for global site: " + globalSite.getName());

        // Fetch timelines - can have either item (specific item) or itemFamily (all items of family)
        String timelineDql = "select id, name, item, item.name, item.deprecated, item.family, item.family.code, itemFamily, itemFamily.code, startTime, endTime from Timeline where site=$1";

        entityStore.executeQuery(timelineDql, globalSite)
                .onFailure(e -> {
                    Console.error("Error fetching timelines", e);
                    Platform.runLater(() -> {
                        availableTimelines.clear();
                        showDangerMessage("Error fetching timelines: " + e.getMessage());
                        timelinesGrid.getChildren().clear();
                    });
                })
                .onSuccess(timelinesResult -> {
                    Console.log("fetchTimelinesForGlobalSite: Fetched " + timelinesResult.size() + " timelines.");
                    availableTimelines.clear();

                    for (Object o : timelinesResult) {
                        Timeline t = (Timeline) o;
                        Item item = t.getItem();
                        ItemFamily itemFamily = t.getItemFamily();

                        // Timeline with specific item: filter out deprecated items
                        if (item != null) {
                            if (item.isDeprecated() == null || !item.isDeprecated()) {
                                availableTimelines.add(t);
                            }
                        }
                        // Timeline with itemFamily (no item): include if family is set
                        else if (itemFamily != null) {
                            availableTimelines.add(t);
                        }
                    }

                    Console.log("fetchTimelinesForGlobalSite: " + availableTimelines.size() + " valid timelines.");

                    Platform.runLater(() -> {
                        if (availableTimelines.isEmpty()) {
                            showDangerMessage("No timelines found for global site '" + globalSite.getName() + "'. Please configure timelines before generating scheduled items.");
                            timelinesGrid.getChildren().clear();
                            resourcesDisplayBox.getChildren().clear();
                        } else {
                            hideDangerMessage();
                            displayTimelines();
                            // Fetch resources for accommodation
                            fetchResourcesForGlobalSite(entityStore);
                        }
                    });
                });
    }

    private void fetchResourcesForGlobalSite(EntityStore entityStore) {
        Console.log("fetchResourcesForGlobalSite: Fetching resources for global site: " + globalSite.getName());

        // Fetch ResourceConfigurations with their resources and items for the global site
        // This tells us which accommodation types (Items) have resources (rooms) at this site
        String configDql = "select id, resource, resource.id, resource.name, resource.building, resource.building.name, " +
                "item, item.id, item.name, item.family, item.family.code " +
                "from ResourceConfiguration where resource.site=$1 order by item.name, resource.building.name, resource.name";

        entityStore.executeQuery(configDql, globalSite)
                .onFailure(e -> {
                    Console.error("Error fetching resource configurations", e);
                    Platform.runLater(() -> {
                        availableResources.clear();
                        accommodationItemsWithResources.clear();
                        resourcesDisplayBox.getChildren().clear();
                    });
                })
                .onSuccess(configResult -> {
                    Console.log("fetchResourcesForGlobalSite: Fetched " + configResult.size() + " resource configurations.");
                    availableResources.clear();
                    accommodationItemsWithResources.clear();

                    // Group resources by Item (accommodation type)
                    Set<Object> seenResourceIds = new HashSet<>();
                    for (Object o : configResult) {
                        ResourceConfiguration rc = (ResourceConfiguration) o;
                        Resource resource = rc.getResource();
                        Item item = rc.getItem();

                        if (resource != null && item != null) {
                            // Track unique resources
                            if (!seenResourceIds.contains(resource.getPrimaryKey())) {
                                seenResourceIds.add(resource.getPrimaryKey());
                                availableResources.add(resource);
                            }

                            // Group by Item
                            accommodationItemsWithResources
                                    .computeIfAbsent(item, k -> new ArrayList<>())
                                    .add(resource);
                        }
                    }

                    Console.log("fetchResourcesForGlobalSite: " + availableResources.size() + " resources, " +
                            accommodationItemsWithResources.size() + " accommodation types available.");

                    Platform.runLater(() -> displayResources());
                });
    }

    private void refreshStatus() {
        statusPane.getChildren().clear();
        statusPane.getChildren().add(new Label("Loading status..."));

        EntityId organizationId = FXOrganizationId.getOrganizationId();
        if (organizationId == null) {
            Console.log("refreshStatus: No organization selected.");
            return;
        }

        // Query to get counts of ScheduledItems per month and family
        // Note: DQL doesn't support complex group by month easily, so we fetch dates
        // and aggregate in Java
        // Display history since 2022
        LocalDate start = LocalDate.of(2022, 1, 1);
        LocalDate end = LocalDate.now().plusYears(2).withDayOfYear(1);

        Console.log("refreshStatus: Fetching ScheduledItems from " + start + " to " + end + " for organization "
                + organizationId);

        String siDql = "select date, item.family.code from ScheduledItem where site.organization.id=$1 and date >= $2 and date < $3 order by date";

        EntityStore entityStore = EntityStore.create();

        entityStore.executeQuery(siDql, organizationId, start, end)
                .onFailure(e -> {
                    Console.error("refreshStatus: Error loading ScheduledItems", e);
                    Platform.runLater(() -> {
                        statusPane.getChildren().setAll(new Label("Error loading status: " + e.getMessage()));
                    });
                })
                .onSuccess(siResult -> {
                    Console.log("refreshStatus: Successfully fetched " + siResult.size() + " ScheduledItems.");

                    Map<YearMonth, Map<String, Long>> counts = new TreeMap<>();

                    // Process ScheduledItems
                    for (Object entity : siResult) {
                        ScheduledItem si = (ScheduledItem) entity;
                        LocalDate date = si.getDate();
                        if (date == null)
                            continue;
                        String familyCode = si.getItem().getFamily().getCode();

                        YearMonth ym = YearMonth.from(date);
                        counts.computeIfAbsent(ym, k -> new HashMap<>())
                                .merge(familyCode, 1L, Long::sum);
                    }

                    Platform.runLater(() -> displayStatus(counts));
                });
    }

    private void displayStatus(Map<YearMonth, Map<String, Long>> counts) {
        Console.log("displayStatus: Displaying counts for " + counts.size() + " months.");
        statusPane.getChildren().clear();

        GridPane grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        // Headers
        grid.add(new Label("Month"), 0, 0);
        grid.add(new Label("Kitchen (Meals)"), 1, 0);
        grid.add(new Label("Accommodation"), 2, 0);

        int row = 1;
        DateTimeFormatter monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy");

        for (Map.Entry<YearMonth, Map<String, Long>> entry : counts.entrySet()) {
            YearMonth ym = entry.getKey();
            Map<String, Long> familyCounts = entry.getValue();

            Label monthLabel = new Label(ym.format(monthFormatter));
            Label mealsLabel = new Label(String.valueOf(familyCounts.getOrDefault("meals", 0L)));
            Label accoLabel = new Label(String.valueOf(familyCounts.getOrDefault("acco", 0L)));

            grid.add(monthLabel, 0, row);
            grid.add(mealsLabel, 1, row);
            grid.add(accoLabel, 2, row);

            row++;
        }

        statusPane.getChildren().add(grid);
    }

    private void generateSchedule(String familyCode) {
        LocalDate from = fromDatePicker.getValue();
        LocalDate to = toDatePicker.getValue();

        Console.log("generateSchedule: Request to generate for family '" + familyCode + "' from " + from + " to " + to);

        if (from == null || to == null || from.isAfter(to)) {
            Console.log("generateSchedule: Invalid date range.");
            showErrorDialog("Invalid Date Range", "Please select a valid date range where 'From' is before 'To'.");
            return;
        }

        EntityId organizationId = FXOrganizationId.getOrganizationId();
        if (organizationId == null) {
            Console.log("generateSchedule: No organization selected.");
            return;
        }

        if (globalSite == null) {
            Console.log("generateSchedule: Global Site is null. Cannot generate.");
            showErrorDialog("No Global Site", "Global Site not found for this organization. Cannot generate scheduled items.");
            return;
        }

        // Filter timelines: item-specific ones by item's family, family-wide ones by itemFamily
        List<Timeline> itemSpecificTimelines = availableTimelines.stream()
                .filter(t -> t.getItem() != null && t.getItem().getFamily() != null
                        && familyCode.equals(t.getItem().getFamily().getCode()))
                .collect(Collectors.toList());

        List<Timeline> familyWideTimelines = availableTimelines.stream()
                .filter(t -> t.getItem() == null && t.getItemFamily() != null
                        && familyCode.equals(t.getItemFamily().getCode()))
                .collect(Collectors.toList());

        if (itemSpecificTimelines.isEmpty() && familyWideTimelines.isEmpty()) {
            Console.log("generateSchedule: No timelines found for family '" + familyCode + "'");
            showErrorDialog("No Timelines Found", "No timelines found for family '" + familyCode + "' at the global site.");
            return;
        }

        progressBar.setVisible(true);
        statusLabel.setVisible(true);
        statusLabel.setText("Preparing generation for " + familyCode + "...");
        progressBar.setProgress(0);

        // Collect items from item-specific timelines
        Map<Object, Item> distinctItems = new LinkedHashMap<>();
        Map<Object, Timeline> itemToTimeline = new HashMap<>();

        for (Timeline timeline : itemSpecificTimelines) {
            Item item = timeline.getItem();
            if (!distinctItems.containsKey(item.getPrimaryKey())) {
                distinctItems.put(item.getPrimaryKey(), item);
                itemToTimeline.put(item.getPrimaryKey(), timeline);
            }
        }

        // If there are family-wide timelines, we need to determine which items to generate
        if (!familyWideTimelines.isEmpty()) {
            // Use the first family-wide timeline for all items of this family
            Timeline familyTimeline = familyWideTimelines.get(0);

            // For accommodation, only generate items that have resources at the global site
            if ("acco".equals(familyCode)) {
                Console.log("generateSchedule: Using items from accommodationItemsWithResources (accommodation with resources at global site)");

                if (accommodationItemsWithResources.isEmpty()) {
                    showErrorDialog("No Accommodation Items", "No accommodation types have rooms linked to the global site. Cannot generate scheduled items.");
                    progressBar.setVisible(false);
                    statusLabel.setVisible(false);
                    return;
                }

                // Add items that have resources at this site
                for (Item item : accommodationItemsWithResources.keySet()) {
                    if (!distinctItems.containsKey(item.getPrimaryKey())) {
                        distinctItems.put(item.getPrimaryKey(), item);
                        itemToTimeline.put(item.getPrimaryKey(), familyTimeline);
                    }
                }

                Console.log("generateSchedule: Found " + distinctItems.size() + " accommodation items with resources");
                proceedWithGeneration(familyCode, distinctItems, itemToTimeline, from, to);
            } else {
                // For non-accommodation (e.g., meals), fetch all non-deprecated items
                Console.log("generateSchedule: Fetching all items for family '" + familyCode + "' (family-wide timeline)");
                String itemDql = "select id, name, family, family.code from Item where family.code=$1 and organization.id=$2 and (deprecated is null or deprecated=false)";

                EntityStore.create().executeQuery(itemDql, familyCode, organizationId)
                        .onFailure(e -> handleError("Error fetching items for family", e))
                        .onSuccess(items -> {
                            Console.log("generateSchedule: Fetched " + items.size() + " items for family '" + familyCode + "'");

                            // Add fetched items (if not already present from item-specific timelines)
                            for (Object o : items) {
                                Item item = (Item) o;
                                if (!distinctItems.containsKey(item.getPrimaryKey())) {
                                    distinctItems.put(item.getPrimaryKey(), item);
                                    itemToTimeline.put(item.getPrimaryKey(), familyTimeline);
                                }
                            }

                            proceedWithGeneration(familyCode, distinctItems, itemToTimeline, from, to);
                        });
            }
        } else {
            // No family-wide timelines, proceed directly with item-specific ones
            proceedWithGeneration(familyCode, distinctItems, itemToTimeline, from, to);
        }
    }

    private void proceedWithGeneration(String familyCode, Map<Object, Item> distinctItems,
                                       Map<Object, Timeline> itemToTimeline, LocalDate from, LocalDate to) {
        List<Item> itemList = new ArrayList<>(distinctItems.values());
        Console.log("generateSchedule: Found " + itemList.size() + " distinct items to generate");

        if (itemList.isEmpty()) {
            handleError("No items found for family: " + familyCode, null);
            return;
        }

        Platform.runLater(() -> statusLabel.setText("Starting generation for " + familyCode + "..."));

        // Use SQL bulk insert for better performance
        generateWithSql(itemList, itemToTimeline, from, to);
    }

    private void generateWithSql(List<Item> items, Map<Object, Timeline> itemToTimeline,
                                 LocalDate from, LocalDate to) {
        Console.log("generateWithSql: Starting SQL-based generation for " + items.size() + " items");
        Console.log("generateWithSql: Date range: " + from + " to " + to);

        // Group items by timeline for efficient batch processing
        Map<Object, List<Item>> itemsByTimeline = new LinkedHashMap<>();
        for (Item item : items) {
            Timeline timeline = itemToTimeline.get(item.getPrimaryKey());
            if (timeline != null) {
                itemsByTimeline.computeIfAbsent(timeline.getPrimaryKey(), k -> new ArrayList<>()).add(item);
            }
        }

        // Build list of SQL statements to execute
        List<String> sqlStatements = new ArrayList<>();

        for (Map.Entry<Object, List<Item>> entry : itemsByTimeline.entrySet()) {
            Object timelineId = entry.getKey();
            List<Item> timelineItems = entry.getValue();

            for (Item item : timelineItems) {
                // SQL for scheduled_item using generate_series
                // Only insert dates that don't already exist
                String sql = "INSERT INTO scheduled_item (date, timeline_id, site_id, item_id, start_time, end_time, available, online, resource) " +
                        "SELECT day, t.id, t.site_id, " + item.getPrimaryKey() + ", NULL, NULL, true, true, " +
                        "EXISTS (SELECT 1 FROM resource_configuration rc JOIN resource r ON r.id = rc.resource_id WHERE rc.item_id = " + item.getPrimaryKey() + " AND r.site_id = t.site_id) " +
                        "FROM timeline t, " +
                        "LATERAL (SELECT generate_series('" + from + "'::date, '" + to + "'::date, '1 day'::interval)::date AS day) days " +
                        "WHERE t.id = " + timelineId + " " +
                        "AND NOT EXISTS (SELECT 1 FROM scheduled_item si WHERE si.date = day AND si.item_id = " + item.getPrimaryKey() + " AND si.site_id = t.site_id)";
                sqlStatements.add(sql);
            }
        }

        if (sqlStatements.isEmpty()) {
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("No items to generate!");
                refreshStatus();
            });
            return;
        }

        Console.log("generateWithSql: Executing " + sqlStatements.size() + " SQL statements");
        Platform.runLater(() -> statusLabel.setText("Generating " + items.size() + " items..."));

        // Execute all statements
        executeNextSqlStatement(sqlStatements, 0);
    }

    private void executeNextSqlStatement(List<String> sqlStatements, int index) {
        if (index >= sqlStatements.size()) {
            // All done with scheduled items
            Console.log("generateWithSql: All scheduled items generated");
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText("Generation complete!");
                refreshStatus();
            });
            return;
        }

        String sql = sqlStatements.get(index);
        int current = index + 1;
        int total = sqlStatements.size();

        Platform.runLater(() -> statusLabel.setText("Generating item " + current + "/" + total + "..."));

        // Execute native SQL update using SubmitService
        SubmitService.executeSubmit(SubmitArgument.builder()
                        .setLanguage("SQL")
                        .setStatement(sql)
                        .setDataSourceId(DataSourceModelService.getDefaultDataSourceId())
                        .build())
                .onFailure(e -> {
                    Console.error("Error executing SQL: " + sql, e);
                    handleError("Error generating scheduled items", e);
                })
                .onSuccess(result -> {
                    Console.log("generateWithSql: Statement " + current + "/" + total + " executed");
                    executeNextSqlStatement(sqlStatements, index + 1);
                });
    }

    private void handleError(String message, Throwable e) {
        Platform.runLater(() -> {
            progressBar.setVisible(false);
            statusLabel.setText("Error: " + message);
            if (e != null)
                Console.error(message, e);
            showErrorDialog("Generation Error", message + (e != null ? "\n\n" + e.getMessage() : ""));
        });
    }

    /**
     * Shows a GWT-compatible error dialog using DialogUtil.
     */
    private void showErrorDialog(String header, String content) {
        VBox dialogContent = new VBox(15);
        dialogContent.setPadding(new Insets(25));
        dialogContent.setMinWidth(300);
        dialogContent.setPrefWidth(450);
        dialogContent.setMaxWidth(600);

        // Title with error icon
        Label titleLabel = new Label("⚠ Error");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #c0392b;");
        titleLabel.setMaxWidth(Double.MAX_VALUE);

        // Header
        Label headerLabel = new Label(header);
        headerLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;");
        headerLabel.setWrapText(true);
        headerLabel.setMaxWidth(Double.MAX_VALUE);

        // Content
        Label contentLabel = new Label(content);
        contentLabel.setStyle("-fx-text-fill: #555;");
        contentLabel.setWrapText(true);
        contentLabel.setMaxWidth(Double.MAX_VALUE);

        dialogContent.getChildren().addAll(titleLabel, headerLabel, contentLabel);

        // OK Button
        HBox footer = new HBox();
        footer.setAlignment(Pos.CENTER_RIGHT);
        footer.setPadding(new Insets(10, 0, 0, 0));

        Button okButton = new Button("OK");
        okButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-padding: 8 20 8 20; -fx-background-radius: 4; -fx-cursor: hand;");

        footer.getChildren().add(okButton);
        dialogContent.getChildren().add(footer);

        // Show dialog using DialogUtil
        BorderPane dialogPane = new BorderPane(dialogContent);
        dialogPane.setStyle("-fx-background-color: white; -fx-background-radius: 8;");
        DialogCallback dialogCallback = DialogUtil.showModalNodeInGoldLayout(dialogPane, FXMainFrameDialogArea.getDialogArea());

        // Button action
        okButton.setOnAction(e -> dialogCallback.closeDialog());
    }

    private void showDangerMessage(String message) {
        dangerMessageLabel.setText(message);
        dangerMessageBox.setVisible(true);
        dangerMessageBox.setManaged(true);
    }

    private void hideDangerMessage() {
        dangerMessageBox.setVisible(false);
        dangerMessageBox.setManaged(false);
    }

    private void displayResources() {
        resourcesDisplayBox.getChildren().clear();

        if (accommodationItemsWithResources.isEmpty()) {
            Label noResourcesLabel = new Label("ℹ No accommodation resources found for this site. No accommodation scheduled items will be generated.");
            noResourcesLabel.setStyle("-fx-text-fill: #856404; -fx-font-style: italic;");
            resourcesDisplayBox.getChildren().add(noResourcesLabel);
            return;
        }

        // Main header
        Label headerLabel = new Label("🏠 Accommodation Generation (" + accommodationItemsWithResources.size() + " types, " + availableResources.size() + " rooms):");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #8e44ad;");
        resourcesDisplayBox.getChildren().add(headerLabel);

        // Subheader explaining the logic
        Label subHeader = new Label("Only these accommodation types will have ScheduledItems generated (based on rooms linked to this site):");
        subHeader.setStyle("-fx-text-fill: #666; -fx-font-size: 11px; -fx-font-style: italic;");
        subHeader.setWrapText(true);
        resourcesDisplayBox.getChildren().add(subHeader);

        // Create content container
        VBox contentBox = new VBox(10);
        contentBox.setPadding(new Insets(8, 0, 0, 10));

        for (Map.Entry<Item, List<Resource>> entry : accommodationItemsWithResources.entrySet()) {
            Item accoType = entry.getKey();
            List<Resource> rooms = entry.getValue();

            // Accommodation type section
            VBox typeSection = new VBox(4);
            typeSection.setStyle("-fx-background-color: #fafbfc; -fx-padding: 8; -fx-background-radius: 6; -fx-border-color: #e1e4e8; -fx-border-radius: 6;");

            // Type header with room count
            HBox typeHeader = new HBox(10);
            typeHeader.setAlignment(Pos.CENTER_LEFT);

            Label typeIcon = new Label("🛏");
            typeIcon.setStyle("-fx-font-size: 14px;");

            Label typeLabel = new Label(accoType.getName());
            typeLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #24292e;");

            Label countBadge = new Label(rooms.size() + " room" + (rooms.size() > 1 ? "s" : ""));
            countBadge.setStyle("-fx-background-color: #8e44ad; -fx-text-fill: white; -fx-padding: 2 8 2 8; -fx-background-radius: 10; -fx-font-size: 11px;");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            typeHeader.getChildren().addAll(typeIcon, typeLabel, spacer, countBadge);
            typeSection.getChildren().add(typeHeader);

            // Group rooms by building for this type
            Map<String, List<Resource>> roomsByBuilding = new LinkedHashMap<>();
            for (Resource r : rooms) {
                String buildingName = r.getBuilding() != null ? r.getBuilding().getName() : "No Building";
                roomsByBuilding.computeIfAbsent(buildingName, k -> new ArrayList<>()).add(r);
            }

            // Display rooms grouped by building
            for (Map.Entry<String, List<Resource>> buildingEntry : roomsByBuilding.entrySet()) {
                String buildingName = buildingEntry.getKey();
                List<Resource> buildingRooms = buildingEntry.getValue();

                HBox buildingRow = new HBox(6);
                buildingRow.setAlignment(Pos.CENTER_LEFT);
                buildingRow.setPadding(new Insets(2, 0, 0, 20));

                Label buildingLabel = new Label(buildingName + ":");
                buildingLabel.setStyle("-fx-text-fill: #586069; -fx-font-size: 11px; -fx-min-width: 80;");

                FlowPane roomsFlow = new FlowPane();
                roomsFlow.setHgap(4);
                roomsFlow.setVgap(3);

                for (Resource room : buildingRooms) {
                    Label roomLabel = new Label(room.getName());
                    roomLabel.setStyle("-fx-background-color: #e8f4fd; -fx-text-fill: #0366d6; -fx-padding: 1 6 1 6; -fx-background-radius: 3; -fx-font-size: 11px;");
                    roomsFlow.getChildren().add(roomLabel);
                }

                buildingRow.getChildren().addAll(buildingLabel, roomsFlow);
                typeSection.getChildren().add(buildingRow);
            }

            contentBox.getChildren().add(typeSection);
        }

        // If many types/rooms, wrap in scrollable container
        if (accommodationItemsWithResources.size() > 3 || availableResources.size() > 30) {
            ScrollPane scrollPane = new ScrollPane(contentBox);
            scrollPane.setFitToWidth(true);
            scrollPane.setMaxHeight(250);
            scrollPane.setStyle("-fx-background-color: transparent; -fx-border-color: #e0e0e0; -fx-border-radius: 4;");
            resourcesDisplayBox.getChildren().add(scrollPane);
        } else {
            resourcesDisplayBox.getChildren().add(contentBox);
        }
    }

    private void displayTimelines() {
        timelinesGrid.getChildren().clear();

        if (availableTimelines.isEmpty()) {
            return;
        }

        // Separate timelines by type
        List<Timeline> itemTimelines = availableTimelines.stream()
                .filter(t -> t.getItem() != null)
                .collect(Collectors.toList());
        List<Timeline> familyTimelines = availableTimelines.stream()
                .filter(t -> t.getItem() == null && t.getItemFamily() != null)
                .collect(Collectors.toList());

        int row = 0;

        // Add header
        Label headerLabel = new Label("Available Timelines for Generation:");
        headerLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #2c3e50;");
        timelinesGrid.add(headerLabel, 0, row++, 4, 1);

        // Section: Item-specific timelines
        if (!itemTimelines.isEmpty()) {
            row++; // spacing
            Label itemSectionLabel = new Label("▸ Specific Items:");
            itemSectionLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #27ae60;");
            timelinesGrid.add(itemSectionLabel, 0, row++, 4, 1);

            // Column headers
            Label itemHeader = new Label("Item");
            itemHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label familyHeader = new Label("Family");
            familyHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label startHeader = new Label("Start");
            startHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label endHeader = new Label("End");
            endHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");

            timelinesGrid.add(itemHeader, 0, row);
            timelinesGrid.add(familyHeader, 1, row);
            timelinesGrid.add(startHeader, 2, row);
            timelinesGrid.add(endHeader, 3, row);
            row++;

            for (Timeline t : itemTimelines) {
                Item item = t.getItem();
                String itemName = item.getName();
                String familyCode = item.getFamily() != null ? item.getFamily().getCode() : "-";
                String startTime = t.getStartTime() != null ? t.getStartTime().toString() : "-";
                String endTime = t.getEndTime() != null ? t.getEndTime().toString() : "-";

                timelinesGrid.add(new Label("  " + itemName), 0, row);
                timelinesGrid.add(new Label(familyCode), 1, row);
                timelinesGrid.add(new Label(startTime), 2, row);
                timelinesGrid.add(new Label(endTime), 3, row);
                row++;
            }
        }

        // Section: Family-wide timelines
        if (!familyTimelines.isEmpty()) {
            row++; // spacing
            Label familySectionLabel = new Label("▸ All Items in Family:");
            familySectionLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #2980b9;");
            timelinesGrid.add(familySectionLabel, 0, row++, 4, 1);

            // Column headers
            Label familyHeader2 = new Label("Family");
            familyHeader2.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label descHeader = new Label("Description");
            descHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label startHeader2 = new Label("Start");
            startHeader2.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");
            Label endHeader2 = new Label("End");
            endHeader2.setStyle("-fx-font-weight: bold; -fx-text-fill: #7f8c8d;");

            timelinesGrid.add(familyHeader2, 0, row);
            timelinesGrid.add(descHeader, 1, row);
            timelinesGrid.add(startHeader2, 2, row);
            timelinesGrid.add(endHeader2, 3, row);
            row++;

            for (Timeline t : familyTimelines) {
                ItemFamily family = t.getItemFamily();
                String familyCode = family.getCode();
                String familyName = "meals".equals(familyCode) ? "Kitchen (Meals)" :
                                   "acco".equals(familyCode) ? "Accommodation" : familyCode;
                String startTime = t.getStartTime() != null ? t.getStartTime().toString() : "-";
                String endTime = t.getEndTime() != null ? t.getEndTime().toString() : "-";

                timelinesGrid.add(new Label("  " + familyCode), 0, row);
                timelinesGrid.add(new Label(familyName + " (all non-deprecated items)"), 1, row);
                timelinesGrid.add(new Label(startTime), 2, row);
                timelinesGrid.add(new Label(endTime), 3, row);
                row++;
            }
        }
    }

}
