// File managed by WebFX (DO NOT EDIT MANUALLY)
package java.util;

import java.util.Iterator;
import java.util.logging.Logger;
import dev.webfx.platform.shared.util.function.Factory;

public class ServiceLoader<S> implements Iterable<S> {

    public static <S> ServiceLoader<S> load(Class<S> serviceClass) {
        switch (serviceClass.getName()) {
            case "dev.webfx.framework.client.operations.i18n.ChangeLanguageRequestEmitter": return new ServiceLoader<S>(mongoose.client.operations.i18n.ChangeLanguageToEnglishRequest.ProvidedEmitter::new, mongoose.client.operations.i18n.ChangeLanguageToFrenchRequest.ProvidedEmitter::new);
            case "dev.webfx.framework.client.operations.route.RouteRequestEmitter": return new ServiceLoader<S>(mongoose.backend.activities.authorizations.RouteToAuthorizationsRequestEmitter::new, mongoose.backend.activities.bookings.RouteToBookingsRequestEmitter::new, mongoose.backend.activities.diningareas.RouteToDiningAreasRequestEmitter::new, mongoose.backend.activities.statistics.RouteToStatisticsRequestEmitter::new, mongoose.backend.activities.events.RouteToEventsRequestEmitter::new, mongoose.backend.activities.income.RouteToIncomeRequestEmitter::new, mongoose.backend.activities.monitor.RouteToMonitorRequestEmitter::new, mongoose.backend.activities.letters.RouteToLettersRequestEmitter::new, mongoose.backend.activities.payments.RouteToPaymentsRequestEmitter::new, mongoose.backend.activities.roomsgraphic.RouteToRoomsGraphicRequestEmitter::new, mongoose.backend.activities.statements.RouteToStatementsRequestEmitter::new, mongoose.backend.activities.users.RouteToUsersRequestEmitter::new, mongoose.backend.activities.operations.RouteToOperationsRequestEmitter::new, mongoose.backend.activities.organizations.RouteToOrganizationsRequestEmitter::new);
            case "dev.webfx.framework.client.services.i18n.spi.I18nProvider": return new ServiceLoader<S>(mongoose.client.services.i18n.MongooseI18nProvider::new);
            case "dev.webfx.framework.client.services.push.spi.PushClientServiceProvider": return new ServiceLoader<S>(dev.webfx.framework.client.services.push.spi.impl.simple.SimplePushClientServiceProvider::new);
            case "dev.webfx.framework.client.ui.uirouter.UiRoute": return new ServiceLoader<S>(mongoose.client.activities.login.LoginUiRoute::new, mongoose.client.activities.unauthorized.UnauthorizedUiRoute::new, mongoose.backend.activities.authorizations.AuthorizationsUiRoute::new, mongoose.backend.activities.bookings.BookingsUiRoute::new, mongoose.backend.activities.cloneevent.CloneEventUiRoute::new, mongoose.backend.activities.diningareas.DiningAreasUiRoute::new, mongoose.backend.activities.statistics.StatisticsUiRoute::new, mongoose.backend.activities.events.EventsUiRoute::new, mongoose.backend.activities.income.IncomeUiRoute::new, mongoose.backend.activities.monitor.MonitorUiRoute::new, mongoose.backend.activities.letters.LettersUiRoute::new, mongoose.backend.activities.letter.LetterUiRoute::new, mongoose.backend.activities.payments.PaymentsUiRoute::new, mongoose.backend.activities.roomsgraphic.RoomsGraphicUiRoute::new, mongoose.backend.activities.statements.StatementsUiRoute::new, mongoose.backend.activities.users.UsersUiRoute::new, mongoose.backend.activities.operations.OperationsUiRoute::new, mongoose.backend.activities.organizations.OrganizationsUiRoute::new);
            case "dev.webfx.framework.shared.orm.entity.EntityFactoryProvider": return new ServiceLoader<S>(mongoose.shared.entities.impl.AttendanceImpl.ProvidedFactory::new, mongoose.shared.entities.impl.CartImpl.ProvidedFactory::new, mongoose.shared.entities.impl.CountryImpl.ProvidedFactory::new, mongoose.shared.entities.impl.DateInfoImpl.ProvidedFactory::new, mongoose.shared.entities.impl.DocumentImpl.ProvidedFactory::new, mongoose.shared.entities.impl.DocumentLineImpl.ProvidedFactory::new, mongoose.shared.entities.impl.EventImpl.ProvidedFactory::new, mongoose.shared.entities.impl.FilterImpl.ProvidedFactory::new, mongoose.shared.entities.impl.GatewayParameterImpl.ProvidedFactory::new, mongoose.shared.entities.impl.HistoryImpl.ProvidedFactory::new, mongoose.shared.entities.impl.ImageImpl.ProvidedFactory::new, mongoose.shared.entities.impl.ItemFamilyImpl.ProvidedFactory::new, mongoose.shared.entities.impl.ItemImpl.ProvidedFactory::new, mongoose.shared.entities.impl.LabelImpl.ProvidedFactory::new, mongoose.shared.entities.impl.MailImpl.ProvidedFactory::new, mongoose.shared.entities.impl.MethodImpl.ProvidedFactory::new, mongoose.shared.entities.impl.MoneyTransferImpl.ProvidedFactory::new, mongoose.shared.entities.impl.OptionImpl.ProvidedFactory::new, mongoose.shared.entities.impl.OrganizationImpl.ProvidedFactory::new, mongoose.shared.entities.impl.OrganizationTypeImpl.ProvidedFactory::new, mongoose.shared.entities.impl.PersonImpl.ProvidedFactory::new, mongoose.shared.entities.impl.RateImpl.ProvidedFactory::new, mongoose.shared.entities.impl.SiteImpl.ProvidedFactory::new, mongoose.shared.entities.impl.SystemMetricsEntityImpl.ProvidedFactory::new, mongoose.shared.entities.impl.TeacherImpl.ProvidedFactory::new);
            case "dev.webfx.framework.shared.services.authn.spi.AuthenticationServiceProvider": return new ServiceLoader<S>(mongoose.client.services.authn.MongooseAuthenticationServiceProvider::new);
            case "dev.webfx.framework.shared.services.authz.spi.AuthorizationServiceProvider": return new ServiceLoader<S>(mongoose.client.services.authz.MongooseAuthorizationServiceProvider::new);
            case "dev.webfx.framework.shared.services.datasourcemodel.spi.DataSourceModelProvider": return new ServiceLoader<S>(mongoose.shared.services.datasourcemodel.MongooseDataSourceModelProvider::new);
            case "dev.webfx.framework.shared.services.domainmodel.spi.DomainModelProvider": return new ServiceLoader<S>(mongoose.shared.services.domainmodel.MongooseDomainModelProvider::new);
            case "dev.webfx.framework.shared.services.querypush.spi.QueryPushServiceProvider": return new ServiceLoader<S>(dev.webfx.framework.client.jobs.querypush.QueryPushClientServiceProvider::new);
            case "dev.webfx.kit.launcher.spi.WebFxKitLauncherProvider": return new ServiceLoader<S>(dev.webfx.kit.launcher.spi.gwt.GwtWebFxKitLauncherProvider::new);
            case "dev.webfx.kit.mapper.spi.WebFxKitMapperProvider": return new ServiceLoader<S>(dev.webfx.kit.mapper.spi.gwt.GwtWebFxKitHtmlMapperProvider::new);
            case "dev.webfx.platform.client.services.storage.spi.LocalStorageProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.storage.spi.impl.GwtLocalStorageProvider::new);
            case "dev.webfx.platform.client.services.storage.spi.SessionStorageProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.storage.spi.impl.GwtSessionStorageProvider::new);
            case "dev.webfx.platform.client.services.uischeduler.spi.UiSchedulerProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.uischeduler.spi.impl.GwtUiSchedulerProvider::new);
            case "dev.webfx.platform.client.services.websocket.spi.WebSocketServiceProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.websocket.spi.impl.GwtWebSocketServiceProvider::new);
            case "dev.webfx.platform.client.services.windowhistory.spi.WindowHistoryProvider": return new ServiceLoader<S>(dev.webfx.platform.web.services.windowhistory.spi.impl.WebWindowHistoryProvider::new);
            case "dev.webfx.platform.client.services.windowlocation.spi.WindowLocationProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.windowlocation.spi.impl.GwtWindowLocationProvider::new);
            case "dev.webfx.platform.gwt.services.resource.spi.impl.GwtResourceBundle": return new ServiceLoader<S>(mongoose.backend.application.gwt.embed.EmbedResourcesBundle.ProvidedGwtResourceBundle::new);
            case "dev.webfx.platform.shared.services.appcontainer.spi.ApplicationContainerProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.appcontainer.spi.impl.GwtApplicationContainerProvider::new);
            case "dev.webfx.platform.shared.services.appcontainer.spi.ApplicationJob": return new ServiceLoader<S>(mongoose.client.jobs.sessionrecorder.ClientSessionRecorderJob::new, dev.webfx.framework.client.jobs.querypush.QueryPushClientJob::new);
            case "dev.webfx.platform.shared.services.appcontainer.spi.ApplicationModuleInitializer": return new ServiceLoader<S>(dev.webfx.kit.launcher.WebFxKitLauncherModuleInitializer::new, dev.webfx.platform.shared.services.appcontainer.spi.impl.ApplicationJobsStarter::new, dev.webfx.platform.shared.services.serial.SerialCodecModuleInitializer::new, dev.webfx.platform.shared.services.buscall.BusCallModuleInitializer::new, mongoose.client.operationactionsloading.MongooseClientOperationActionsLoader::new, dev.webfx.framework.shared.interceptors.dqlquery.DqlQueryInterceptorModuleInitializer::new, dev.webfx.framework.shared.interceptors.dqlquerypush.DqlQueryPushInterceptorModuleInitializer::new, dev.webfx.framework.shared.interceptors.dqlsubmit.DqlSubmitInterceptorModuleInitializer::new, dev.webfx.platform.gwt.services.resource.spi.impl.GwtResourceModuleInitializer::new);
            case "dev.webfx.platform.shared.services.bus.spi.BusServiceProvider": return new ServiceLoader<S>(dev.webfx.platform.client.services.websocketbus.web.WebWebsocketBusServiceProvider::new);
            case "dev.webfx.platform.shared.services.buscall.spi.BusCallEndpoint": return new ServiceLoader<S>(dev.webfx.platform.shared.services.submit.ExecuteSubmitBusCallEndpoint::new, dev.webfx.platform.shared.services.submit.ExecuteSubmitBatchBusCallEndpoint::new, dev.webfx.platform.shared.services.query.ExecuteQueryBusCallEndpoint::new, dev.webfx.platform.shared.services.query.ExecuteQueryBatchBusCallEndpoint::new, dev.webfx.framework.shared.services.querypush.ExecuteQueryPushBusCallEndpoint::new);
            case "dev.webfx.platform.shared.services.datasource.spi.LocalDataSourceProvider": return new ServiceLoader<S>();
            case "dev.webfx.platform.shared.services.json.spi.JsonProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.json.spi.impl.GwtJsonObject::create);
            case "dev.webfx.platform.shared.services.log.spi.LoggerProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.log.spi.impl.GwtLoggerProvider::new);
            case "dev.webfx.platform.shared.services.query.spi.QueryServiceProvider": return new ServiceLoader<S>(dev.webfx.platform.shared.services.query.spi.impl.remote.RemoteQueryServiceProvider::new);
            case "dev.webfx.platform.shared.services.resource.spi.ResourceServiceProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.resource.spi.impl.GwtResourceServiceProvider::new);
            case "dev.webfx.platform.shared.services.scheduler.spi.SchedulerProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.uischeduler.spi.impl.GwtUiSchedulerProvider::new);
            case "dev.webfx.platform.shared.services.serial.spi.SerialCodec": return new ServiceLoader<S>(dev.webfx.platform.shared.datascope.aggregate.AggregateScope.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.serial.spi.impl.ProvidedBatchSerialCodec::new, dev.webfx.platform.shared.services.submit.SubmitArgument.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.submit.SubmitResult.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.submit.GeneratedKeyBatchIndex.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.buscall.BusCallArgument.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.buscall.BusCallResult.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.buscall.SerializableAsyncResult.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.query.QueryArgument.ProvidedSerialCodec::new, dev.webfx.platform.shared.services.query.QueryResult.ProvidedSerialCodec::new, dev.webfx.framework.shared.services.querypush.QueryPushArgument.ProvidedSerialCodec::new, dev.webfx.framework.shared.services.querypush.QueryPushResult.ProvidedSerialCodec::new, dev.webfx.framework.shared.services.querypush.diff.impl.QueryResultTranslation.ProvidedSerialCodec::new);
            case "dev.webfx.platform.shared.services.shutdown.spi.ShutdownProvider": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.shutdown.spi.impl.GwtShutdownProvider::new);
            case "dev.webfx.platform.shared.services.submit.spi.SubmitServiceProvider": return new ServiceLoader<S>(dev.webfx.platform.shared.services.submit.spi.impl.remote.RemoteSubmitServiceProvider::new);
            case "dev.webfx.platform.web.services.windowhistory.spi.impl.JsWindowHistory": return new ServiceLoader<S>(dev.webfx.platform.gwt.services.windowhistory.spi.impl.GwtJsWindowHistory::new);
            case "javafx.application.Application": return new ServiceLoader<S>(mongoose.backend.application.MongooseBackendApplication::new);

            // UNKNOWN SPI
            default:
                Logger.getLogger(ServiceLoader.class.getName()).warning("Unknown " + serviceClass + " SPI - returning no provider");
                return new ServiceLoader<S>();
        }
    }

    private final Factory[] factories;

    public ServiceLoader(Factory... factories) {
        this.factories = factories;
    }

    public Iterator<S> iterator() {
        return new Iterator<S>() {
            int index = 0;
            @Override
            public boolean hasNext() {
                return index < factories.length;
            }

            @Override
            public S next() {
                return (S) factories[index++].create();
            }
        };
    }
}