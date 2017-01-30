package com.patientping.hl7.inbound.web;

import com.patientping.api.ElasticFacade;
import com.patientping.api.impl.HL7FacadeImpl;
import com.patientping.api.impl.IndexingFacade;
import com.patientping.api.impl.MatchingToolApiClient;
import com.patientping.ejb.bean.AuditManagerBean;
import com.patientping.ejb.careteam.InstructionManagerBean;
import com.patientping.ejb.dao.bean.*;
import com.patientping.ejb.dashboard.census.CensusEventManagerBean;
import com.patientping.ejb.geolocation.GeoLocationManager;
import com.patientping.ejb.geolocation.GeoLocationManagerBean;
import com.patientping.ejb.history.EventHistoryManager;
import com.patientping.ejb.history.EventHistoryManagerBean;
import com.patientping.ejb.history.filtering.patient.encounter.event.EncounterHistoryFilterBuilderBean;
import com.patientping.ejb.history.filtering.patient.encounter.event.filter.filters.EventRequiresPingForConfiguredConsumerFilter;
import com.patientping.ejb.history.filtering.patient.encounter.event.filter.filters.OptInEncounterHistoryFilter;
import com.patientping.ejb.history.filtering.patient.encounter.event.filter.filters.ViewingGroupCanViewOwnDataFilter;
import com.patientping.ejb.indexing.*;
import com.patientping.ejb.indexing.observer.KafkaReindexingSenderObserverBean;
import com.patientping.ejb.matching.ElasticFacadeBean;
import com.patientping.ejb.matching.client.IndexClient;
import com.patientping.ejb.matching.client.SearchClient;
import com.patientping.ejb.matching.client.bean.IndexClientBean;
import com.patientping.ejb.matching.client.bean.SearchClientBean;
import com.patientping.ejb.matching.observer.bean.PatientEntityIndexingObserverBean;
import com.patientping.ejb.outbound.KafkaMessageSender;
import com.patientping.ejb.outbound.OutboundEventGenerator;
import com.patientping.ejb.outbound.QualityPublisherBean;
import com.patientping.ejb.outbound.kafka.KafkaMessageSenderBean;
import com.patientping.ejb.outbound.kafka.KafkaSenderBean;
import com.patientping.ejb.patient.PatientWriteManager;
import com.patientping.ejb.patient.PatientWriteManagerBean;
import com.patientping.ejb.patient.RosterPatientFacetBuilder;
import com.patientping.ejb.patient.RosterPatientFacetBuilderBean;
import com.patientping.ejb.pings.PingConsistencyUpdaterBean;
import com.patientping.ejb.pings.PingGenerator;
import com.patientping.ejb.pings.PingGeneratorBean;
import com.patientping.ejb.pings.filtering.patient.roster.event.RosterPatientEventNotificationFilterBuilderBean;
import com.patientping.ejb.pings.filtering.patient.roster.event.filter.RosterPatientEventNotificationFilter;
import com.patientping.ejb.pings.filtering.patient.roster.event.filter.filters.*;
import com.patientping.ejb.pmpi.PmpiLoadingService;
import com.patientping.ejb.searching.PatientSearcher;
import com.patientping.elasticsearch.client.ElasticPingClient;
import com.patientping.elasticsearch.client.ElasticPingClientBuilder;
import com.patientping.hl7.handling.dao.HL7EncounterDao;
import com.patientping.hl7.handling.dao.HL7EncounterDaoBean;
import com.patientping.hl7.handling.hl7msgs.*;
import com.patientping.hl7.handling.hl7msgs.event.A11EventFinder;
import com.patientping.hl7.handling.hl7msgs.event.A12EventFinder;
import com.patientping.hl7.handling.hl7msgs.event.A13EventFinder;
import com.patientping.hl7.inbound.kafka.KafkaConfig;
import com.patientping.hl7.mapping.HL7MsgMapperBean;
import com.patientping.jpa.entity.InboundMsgProcessedEntity;
import com.patientping.jpa.entity.Ping;
import com.patientping.jpa.entity.disposition.DispositionEntity;
import com.patientping.jpa.entity.security.UserEntity;
import com.patientping.toggle.PingTogglzBootstrap;
import feign.RequestInterceptor;
import feign.auth.BasicAuthRequestInterceptor;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.client.Requests;
import org.elasticsearch.client.transport.TransportClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.DataSourceBuilder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.togglz.core.bootstrap.TogglzBootstrap;

import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;

@Configuration
@EnableFeignClients(basePackageClasses = {MatchingToolApiClient.class})
@ComponentScan(basePackageClasses = {IndexingFacade.class}, basePackages = {"com.patientping.ejb"})
public class MainConfig {

    @Value("${ping.messaging.topic.quality:quality_event}")
    private String QUALITY_TOPIC_NAME;
    @Value("${ping.messaging.topic.manual_event:manual_event}")
    private String MANUAL_EVENT_TOPIC_NAME;
    @Value("${ping.messaging.topic.automatic_event:automatic_event}")
    private String AUTOMATIC_EVENT_TOPIC_NAME;
    @Value("${ping.messaging.topic.snf_adt_event:snf_adt_event}")
    private String SNF_ADT_EVENT_TOPIC_NAME;
    @Value("${ping.messaging.topic.roster_patient_indexing:roster_patient_indexing_update}")
    private String ROSTER_PATIENT_INDEXING_UPDATE_TOPIC_NAME;
    @Value("${ping.messaging.topic.event_createupdate_indexing:event_indexing_update}")
    private String EVENT_CREATE_UPDATE_INDEXING_TOPIC_NAME;
    @Value("${ping.messaging.topic.pmpi_indexing:pmpi_indexing}")
    private String PMPI_INDEXING_TOPIC_NAME;
    @Value("${ping.messaging.topic.encounter_indexing:encounter_indexing}")
    private String ENCOUNTER_INDEXING_TOPIC_NAME;
    @Value("${ping.messaging.topic.clustering_patients:clustering_patients}")
    private String PMPI_CLUSTERING_TOPIC_NAME;
    @Value("${ping.messaging.topic.bulk_clustering_patients:bulk_clustering_patients}")
    private String PMPI_BULK_CLUSTERING_TOPIC_NAME;
    @Value("${hollaback.threshold:10}")
    private int HOLLABACK_THRESHOLD;
    @Value("${snf.url:https://snf.patientping.com}")
    private String SNF_URL;
    @Value("${janus.url:https://my.patientping.com}")
    private String JANUS_URL;
    @Value("${care.url:https://care.patientping.com}")
    private String CARE_URL;
    @Value("${matchingtool.username}")
    private String matchingToolUsername;
    @Value("${matchingtool.password}")
    private String matchingToolPassword;

    @Autowired
    private KafkaConfig kafkaConfig;


    @Primary
    @Bean(name = "readWriteDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.ping")
    public DataSource readWriteDataSource() {
        return DataSourceBuilder.create().build();
    }


    @Bean(name = "inboundMsgProcessingDataSource")
    @ConfigurationProperties(prefix = "spring.datasource.inbound")
    public DataSource inboundMsgProcessingDataSource() {
        return DataSourceBuilder.create().build();
    }


    @Bean
    public RequestInterceptor basicAuthRequestInterceptor() {
        return new BasicAuthRequestInterceptor(matchingToolUsername, matchingToolPassword);
    }

    @Primary
    @Bean(name = "readWriteEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean readWriteEntityManagerFactory(EntityManagerFactoryBuilder builder,
                                                                                @Qualifier("readWriteDataSource") DataSource dataSource) {
        return builder.dataSource(dataSource)
                .packages(Ping.class, UserEntity.class, DispositionEntity.class)
                .persistenceUnit("patientping_service_PU")
                .build();
    }


    @Bean(name = "inboundMsgEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean inboundMsgEntityManagerFactory(EntityManagerFactoryBuilder builder,
                                                                                 @Qualifier("inboundMsgProcessingDataSource") DataSource dataSource) {
        return builder.dataSource(inboundMsgProcessingDataSource())
                .packages(InboundMsgProcessedEntity.class)
                .persistenceUnit("inbound_service_PU")
                .build();
    }


    @Primary
    @Bean(name = "readWriteTransactionManager")
    public PlatformTransactionManager readWriteTransactionManager(
            @Qualifier(value = "readWriteEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }


    @Bean(name = "inboundTransactionManager")
    public PlatformTransactionManager inboundTransactionManager(
            @Qualifier(value = "inboundMsgEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }


    @Bean
    public GeoLocationManager geoLocationManager() {
        return new GeoLocationManagerBean();
    }

    @Bean
    public Indexer indexer() {
        return new IndexerBean();
    }

    @Bean(name = "EncounterHistoryFilterBuilderBean")
    public EncounterHistoryFilterBuilderBean encounterHistoryFilterBuilder() {
        return new EncounterHistoryFilterBuilderBean();
    }

    @Bean(name = "EventRequiresPingForConfiguredConsumerFilter")
    public EventRequiresPingForConfiguredConsumerFilter eventRequiresPingForConfiguredConsumerFilter() {
        return new EventRequiresPingForConfiguredConsumerFilter();
    }

    @Bean(name = "ViewingGroupCanViewOwnDataFilter")
    public ViewingGroupCanViewOwnDataFilter viewingGroupCanViewOwnDataFilter() {
        return new ViewingGroupCanViewOwnDataFilter();
    }

    @Bean
    public OptInEncounterHistoryFilter OptInEncounterHistoryFilter() {
        return new OptInEncounterHistoryFilter();
    }

    @Bean
    public TogglzBootstrap togglzBootstrap() {
        return new PingTogglzBootstrap();
    }

    @Bean
    public String qualityTopicName() {
        return QUALITY_TOPIC_NAME;
    }

    @Bean
    public String janusDefaultUrl() {
        return JANUS_URL;
    }

    @Bean
    public String careDefaultUrl() {
        return CARE_URL;
    }

    @Bean
    public String snfDefaultUrl() {
        return SNF_URL;
    }

    @Bean
    public String rosterPatientIndexingUpdateTopicName() {
        return ROSTER_PATIENT_INDEXING_UPDATE_TOPIC_NAME;
    }

    @Bean
    public String pmpiIndexingTopicName() {
        return PMPI_INDEXING_TOPIC_NAME;
    }

    @Bean
    public String encounterIndexingTopicName() {
        return ENCOUNTER_INDEXING_TOPIC_NAME;
    }

    @Bean
    public String pmpiClusteringTopicName() {
        return PMPI_CLUSTERING_TOPIC_NAME;
    }

    @Bean
    public String pmpiBulkClusteringTopicName() {
        return PMPI_BULK_CLUSTERING_TOPIC_NAME;
    }

    @Bean
    public Integer hollabackThreshold() {
        return HOLLABACK_THRESHOLD;
    }

    @Bean
    public SearchClient searchClient(ElasticPingClient elasticPingClient) {
        return new SearchClientBean(elasticPingClient);
    }

    @Bean
    public IndexClient indexClient(ElasticPingClient elasticPingClient) {
        return new IndexClientBean(elasticPingClient);
    }

    @Bean
    @Scope("prototype")
    public ElasticPingClient elasticClient(@Value("${es.cluster.name}") String CLUSTER_NAME,
                                           @Value("${es.cluster.host:127.0.0.1}") String CLUSTER_HOST,
                                           @Value("${es.cluster.port:9300}") int CLUSTER_PORT,
                                           @Value("${es.ssl.provider:none}") String SSL_PROVIDER,
                                           @Value("${es.ssl.hostname_verification:ip}") String SSL_HOSTNAME_VERIFICATION,
                                           @Value("${es.ssl.keystore:null}") String SSL_KEYSTORE_PATH,
                                           @Value("${es.ssl.keystore_password:null}") String SSL_KEYSTORE_PASSWORD,
                                           @Value("${es.ssl.truststore:null}") String SSL_TRUSTSTORE_PATH,
                                           @Value("${es.ssl.truststore_password:null}") String SSL_TRUSTSTORE_PASSWORD,
                                           @Value("${es.ssl.principal:null}") String SSL_PRINCIPAL
    ) {
        return new ElasticPingClientBuilder().
                setHost(CLUSTER_HOST).
                setPort(CLUSTER_PORT).
                setClusterName(CLUSTER_NAME).
                setSslProvider(SSL_PROVIDER).
                setKeystorePath(SSL_KEYSTORE_PATH).
                setTrustStorePath(SSL_TRUSTSTORE_PATH).
                setKeyStorePassword(SSL_KEYSTORE_PASSWORD).
                setTrustStorePassword(SSL_TRUSTSTORE_PASSWORD).
                setPrincipal(SSL_PRINCIPAL).
                setHostnameVerification(SSL_HOSTNAME_VERIFICATION).
                createElasticPingClient();
    }

    @Bean
    public HealthIndicator elasticHealthIndicator(ElasticPingClient elasticPingClient) {

        final TransportClient client = elasticPingClient.getClient();

        return new AbstractHealthIndicator() {

            @Override
            protected void doHealthCheck(Health.Builder builder) throws Exception {

                final ClusterHealthResponse response = client.admin()
                        .cluster()
                        .health(Requests.clusterHealthRequest("_all"))
                        .actionGet();

                switch (response.getStatus()) {
                    case GREEN:
                    case YELLOW:
                        builder.up();
                        break;
                    case RED:
                    default:
                        builder.down();
                        break;
                }

                builder.withDetail("clusterName", response.getClusterName())
                        .withDetail("numberOfNodes", response.getNumberOfNodes())
                        .withDetail("numberOfDataNodes", response.getNumberOfDataNodes())
                        .withDetail("activePrimaryShards", response.getActivePrimaryShards())
                        .withDetail("activeShards", response.getActiveShards())
                        .withDetail("relocatingShards", response.getRelocatingShards())
                        .withDetail("initializingShards", response.getInitializingShards())
                        .withDetail("unassignedShards", response.getUnassignedShards());
            }
        };
    }


    @Bean
    public PingGenerator notificationGenerator() {
        return new PingGeneratorBean();
    }

    @Bean(name = "RosterPatientEventNotificationFilterBuilderBean")
    public RosterPatientEventNotificationFilter rosterPatientEventNotificationFilterBuilder() {
        return new RosterPatientEventNotificationFilterBuilderBean();
    }

    @Bean(name = "ActiveRosterPatientFilterBean")
    public ActiveRosterPatientFilterBean activeRosterPatientFilterBean() {
        return new ActiveRosterPatientFilterBean();
    }

    @Bean(name = "EventStatusFilterBean")
    public EventStatusFilterBean eventStatusFilterBean() {
        return new EventStatusFilterBean();
    }

    @Bean(name = "HL7DataSourcePingableGroupWhiteListFilterBean")
    public HL7DataSourcePingableGroupWhiteListFilterBean hl7DataSourcePingableGroupWhiteListFilterBean() {
        return new HL7DataSourcePingableGroupWhiteListFilterBean();
    }

    @Bean(name = "HL7MsgNpiGroupWhiteListFilterBean")
    public HL7MsgNpiGroupWhiteListFilterBean hl7MsgNpiGroupWhiteListFilterBean() {
        return new HL7MsgNpiGroupWhiteListFilterBean();
    }

    @Bean(name = "ExistingPingFilter")
    public ExistingPingFilter existingPingFilter() {
        return new ExistingPingFilter();
    }

    @Bean(name = "HL7MsgPingableGroupsFilter")
    public HL7MsgPingableGroupsFilter hl7MsgPingableGroupsFilter() {
        return new HL7MsgPingableGroupsFilter();
    }

    @Bean(name = "SameAdmittingFacilityAsPingReceiverFilterBean")
    public SameAdmittingFacilityAsPingReceiverFilterBean sameEventSnfPatientFilterBean() {
        return new SameAdmittingFacilityAsPingReceiverFilterBean();
    }

    @Bean
    public HL7EncounterDao hl7EncounterDao() {
        return new HL7EncounterDaoBean();
    }

    @Bean
    public ElasticFacade elasticFacade(SearchClient searchClient, IndexClient indexClient) {
        return new ElasticFacadeBean(searchClient, indexClient);
    }

    @Bean
    public HL7FacadeImpl hl7Facade(GeneralHL7HandlerBean mGeneralHL7Handler) {
        return new HL7FacadeImpl(mGeneralHL7Handler);
    }

    @Bean(name = "KafkaHL7MessageSender")
    public KafkaMessageSender kafkaHL7MessageSender() {
        return new KafkaMessageSenderBean(MANUAL_EVENT_TOPIC_NAME, AUTOMATIC_EVENT_TOPIC_NAME,
                SNF_ADT_EVENT_TOPIC_NAME);
    }

    @Bean
    public HL7MsgFactory hl7MsgFactory(HL7CancelHandlerBean mCancelHandler, HL7PatientUpdaterBean mPatientUpdater,
                                       HL7EventHandlerBean mHl7EventHandler) {
        return new HL7MsgFactory(mCancelHandler, mPatientUpdater, mHl7EventHandler);
    }

    @Bean(name = "A11EventFinder")
    public A11EventFinder a11EventFinder() {
        return new A11EventFinder();
    }

    @Bean(name = "A13EventFinder")
    public A13EventFinder a13EventFinder() {
        return new A13EventFinder();
    }

    @Bean(name = "A12EventFinder")
    public A12EventFinder a12EventFinder() {
        return new A12EventFinder();
    }

    @Bean
    public HL7MsgMapperBean hl7MsgMapper(HL7GroupOIDMappingDao groupOIDMappingDao,
                                         HL7CodeMappingDao hl7CodeMappingDao) {
        return new HL7MsgMapperBean(groupOIDMappingDao, hl7CodeMappingDao);
    }

    @Bean
    public PatientWriteManager patientWriteManager() {
        return new PatientWriteManagerBean();
    }

    @Bean
    public BatchIndexManager batchIndexManager() {
        return new BatchIndexManagerBean();
    }

    @Bean
    public BatchEntityManager batchEntityManager() {
        return new BatchEntityManagerBean();
    }

    @Bean
    public AsyncReindexManager asyncReindexManager() {
        return new AsyncReindexManagerBean();
    }

    @Bean
    public GeneralHL7HandlerBean generalHL7Handler(HL7PreProcessorBean mHL7Preprocessor,
                                                   OutboundEventGenerator outboundEventGenerator, HL7MsgDao mHl7MsgDao,
                                                   HL7MsgFactory mhl7MsgFactory,
                                                   PingConsistencyUpdaterBean pingConsistencyUpdater,
                                                   PatientEventDao mPatientEventDao,
                                                   QualityPublisherBean qualityPublisherBean,
                                                   InboundMsgProcessedDao inboundMsgProcessedDao) {
        return new GeneralHL7HandlerBean(mHL7Preprocessor, outboundEventGenerator, mHl7MsgDao, mhl7MsgFactory,
                pingConsistencyUpdater, mPatientEventDao, qualityPublisherBean, inboundMsgProcessedDao);
    }

    @Bean
    public OutboundEventGenerator outboundEventGenerator(ApplicationEventPublisher applicationEventPublisher,
                                                         KafkaMessageSender kafkaMessageSender,
                                                         AuditManagerBean auditManager) {
        return new OutboundEventGenerator(applicationEventPublisher, kafkaMessageSender, auditManager);
    }


    @Bean
    public HL7PreProcessorBean hl7PreProcessor(HL7MsgMapperBean hl7MsgMapper, PatientSearcher patientSearcher) {
        return new HL7PreProcessorBean(hl7MsgMapper, patientSearcher);
    }

    @Bean
    public HL7CancelHandlerBean hl7CancelHandler(HL7MsgDao hl7MsgDao, CensusEventManagerBean patientEventManager,
                                                 RosterPatientDao mPatientDao, A11EventFinder a11EventFinder,
                                                 A13EventFinder a13Eventfinder, A12EventFinder a12EventFinder) {
        return new HL7CancelHandlerBean(hl7MsgDao, patientEventManager, mPatientDao, a11EventFinder, a13Eventfinder,
                a12EventFinder);
    }

    @Bean
    public HL7EventHandlerBean hl7EventHandler(PatientWriteManager patientWriteManager, GroupDao groupDao,
                                               CensusEventManagerBean patientEventManager,
                                               HL7EncounterDao mHl7EncounterDao, RosterPatientDao rosterPatientDao,
                                               HL7MsgDao hl7MsgDao) {
        return new HL7EventHandlerBean(patientWriteManager, groupDao, patientEventManager, mHl7EncounterDao,
                rosterPatientDao, hl7MsgDao);
    }

    @Bean
    public HL7PatientUpdaterBean hl7PatientUpdater(RosterPatientDao mPatientDao,
                                                   PatientWriteManager patientWriteManager,
                                                   HL7EncounterDao mHl7EncounterDao,
                                                   CensusEventManagerBean censusEventManagerBean) {
        return new HL7PatientUpdaterBean(patientWriteManager, mPatientDao, mHl7EncounterDao, censusEventManagerBean);
    }


    @Bean
    public EventHistoryManager eventHistoryManager() {
        return new EventHistoryManagerBean();
    }

    @Bean
    public PatientEntityIndexingObserverBean patientEntityIndexingObserverBean() {
        return new PatientEntityIndexingObserverBean();
    }

    @Bean
    public KafkaReindexingSenderObserverBean kafkaReindexingSenderObserverBean() {
        return new KafkaReindexingSenderObserverBean();
    }

    @Bean
    public KafkaSenderBean kafkaSenderBean() {
        return kafkaConfig.buildKafkaSender();
    }

    @Bean
    public RosterPatientFacetBuilder rosterPatientFacetBuilder() {
        return new RosterPatientFacetBuilderBean();
    }

    @Bean
    public InstructionManagerBean instructionManagerBean(InstructionDao instructionDao,
                                                         PmpiLoadingService pmpiLoadingService) {
        return new InstructionManagerBean(instructionDao, pmpiLoadingService);
    }
}