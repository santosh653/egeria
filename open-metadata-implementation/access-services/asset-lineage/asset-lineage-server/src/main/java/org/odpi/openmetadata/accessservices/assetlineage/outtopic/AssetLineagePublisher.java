/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright Contributors to the ODPi Egeria project. */
package org.odpi.openmetadata.accessservices.assetlineage.outtopic;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.odpi.openmetadata.accessservices.assetlineage.event.AssetLineageEventHeader;
import org.odpi.openmetadata.accessservices.assetlineage.event.AssetLineageEventType;
import org.odpi.openmetadata.accessservices.assetlineage.event.LineageEntityEvent;
import org.odpi.openmetadata.accessservices.assetlineage.event.LineageRelationshipEvent;
import org.odpi.openmetadata.accessservices.assetlineage.event.LineageRelationshipsEvent;
import org.odpi.openmetadata.accessservices.assetlineage.handlers.ClassificationHandler;
import org.odpi.openmetadata.accessservices.assetlineage.handlers.GlossaryContextHandler;
import org.odpi.openmetadata.accessservices.assetlineage.handlers.ProcessContextHandler;
import org.odpi.openmetadata.accessservices.assetlineage.model.LineageEntity;
import org.odpi.openmetadata.accessservices.assetlineage.model.LineageRelationship;
import org.odpi.openmetadata.accessservices.assetlineage.model.RelationshipsContext;
import org.odpi.openmetadata.accessservices.assetlineage.server.AssetLineageInstanceHandler;
import org.odpi.openmetadata.frameworks.connectors.ffdc.ConnectorCheckedException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.OCFCheckedExceptionBase;
import org.odpi.openmetadata.frameworks.connectors.ffdc.PropertyServerException;
import org.odpi.openmetadata.frameworks.connectors.ffdc.UserNotAuthorizedException;
import org.odpi.openmetadata.repositoryservices.connectors.openmetadatatopic.OpenMetadataTopicConnector;
import org.odpi.openmetadata.repositoryservices.connectors.stores.metadatacollectionstore.properties.instances.EntityDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.odpi.openmetadata.accessservices.assetlineage.util.AssetLineageConstants.GLOSSARY_CATEGORY;
import static org.odpi.openmetadata.accessservices.assetlineage.util.AssetLineageConstants.GLOSSARY_TERM;

/**
 * AssetLineagePublisher is the connector responsible for publishing lineage context information about
 * new and changed assets.
 */
public class AssetLineagePublisher {

    private static final Logger log = LoggerFactory.getLogger(AssetLineagePublisher.class);
    private static AssetLineageInstanceHandler instanceHandler = new AssetLineageInstanceHandler();
    private final OpenMetadataTopicConnector outTopicConnector;
    private final String serverUserName;
    private final ProcessContextHandler processContextHandler;
    private final ClassificationHandler classificationHandler;
    private final GlossaryContextHandler glossaryHandler;

    /**
     * The constructor is given the connection to the out topic for Asset Lineage OMAS
     * along with classes for testing and manipulating instances.
     *
     * @param outTopicConnector connection to the out topic
     * @param serverName        name of the user of the server instance
     * @param serverUserName    name of this server instance
     */
    public AssetLineagePublisher(OpenMetadataTopicConnector outTopicConnector, String serverName, String serverUserName) throws
                                                                                                                         OCFCheckedExceptionBase {
        String methodName = "AssetLineagePublisher";

        this.outTopicConnector = outTopicConnector;
        this.serverUserName = serverUserName;
        this.processContextHandler = instanceHandler.getProcessHandler(serverUserName, serverName, methodName);
        this.classificationHandler = instanceHandler.getClassificationHandler(serverUserName, serverName, methodName);
        this.glossaryHandler = instanceHandler.getGlossaryHandler(serverUserName, serverName, methodName);
    }

    /**
     * Takes the context for a Process and publishes the event to the output topic
     *
     * @param entityDetail entity to get context
     *
     * @return the Process context
     *
     * @throws OCFCheckedExceptionBase checked exception for reporting errors found when using OCF connectors
     * @throws JsonProcessingException exception parsing the event json
     */
    public Multimap<String, RelationshipsContext> publishProcessContext(EntityDetail entityDetail) throws OCFCheckedExceptionBase,
                                                                                                          JsonProcessingException {
        Multimap<String, RelationshipsContext> processContext = processContextHandler.buildProcessContext(serverUserName, entityDetail);

        if (processContext.isEmpty()) {
            log.info("Context not found for the entity {} ", entityDetail.getGUID());
        }

        publishLineageRelationshipsEvents(processContext);

        return processContext;
    }

    /**
     * Build the context for a Glossary Term based on the glossary term GUID and publishes the event to the out topic
     *
     * @param glossaryTermGUID glossary term GUID to get context
     *
     * @throws OCFCheckedExceptionBase checked exception for reporting errors found when using OCF connectors
     * @throws JsonProcessingException exception parsing the event json
     */
    public void publishGlossaryContext(String glossaryTermGUID) throws OCFCheckedExceptionBase, JsonProcessingException {
        EntityDetail entityDetail = glossaryHandler.getGlossaryTermDetails(serverUserName, glossaryTermGUID);
        publishGlossaryContext(entityDetail);
    }

    /***
     * Build the context for a Glossary Term and publishes the event to the out topic
     *
     * @param entityDetail glossary term to get context

     * @return the Glossary Term context
     *
     * @throws OCFCheckedExceptionBase checked exception for reporting errors found when using OCF connectors
     * @throws JsonProcessingException exception parsing the event json
     */
    public Multimap<String, RelationshipsContext> publishGlossaryContext(EntityDetail entityDetail) throws OCFCheckedExceptionBase,
                                                                                                           JsonProcessingException {
        Multimap<String, RelationshipsContext> glossaryTermContext = glossaryHandler.buildGlossaryTermContext(serverUserName, entityDetail);

        if (glossaryTermContext.isEmpty()) {
            log.info("Context not found for the entity {} ", entityDetail.getGUID());
        }

        publishLineageRelationshipsEvents(glossaryTermContext);


        return glossaryTermContext;
    }

    /**
     * Publishes a {@link LineageRelationshipsEvent} for each entry from the context map
     *
     * @param contextMap the context map to be published
     *
     * @throws ConnectorCheckedException unable to send the event due to connectivity issue
     * @throws JsonProcessingException   exception parsing the event json
     */
    private void publishLineageRelationshipsEvents(Multimap<String, RelationshipsContext> contextMap) throws JsonProcessingException,
                                                                                                             ConnectorCheckedException {
        for (String eventType : contextMap.keySet()) {
            for (RelationshipsContext relationshipsContext : contextMap.get(eventType)) {
                if (CollectionUtils.isNotEmpty(relationshipsContext.getRelationships())) {
                    LineageRelationshipsEvent event = new LineageRelationshipsEvent();

                    event.setRelationshipsContext(relationshipsContext);
                    event.setAssetLineageEventType(AssetLineageEventType.getByEventTypeName(eventType));

                    publishEvent(event);
                }
            }
        }
    }

    /**
     * @param entityDetail          entity to get context
     * @param assetLineageEventType event type to get published
     *
     * @throws OCFCheckedExceptionBase checked exception for reporting errors found when using OCF connectors
     * @throws JsonProcessingException exception parsing the event json
     */
    public void publishClassificationContext(EntityDetail entityDetail, AssetLineageEventType assetLineageEventType)
            throws OCFCheckedExceptionBase, JsonProcessingException {
        Map<String, RelationshipsContext> classificationContext = classificationHandler.buildClassificationContext(entityDetail,
                assetLineageEventType);

        if (MapUtils.isEmpty(classificationContext)) {
            log.debug("Lineage classifications not found for the entity {} ", entityDetail.getGUID());
            return;
        }

        publishLineageRelationshipsEvents(Multimaps.forMap(classificationContext));
    }


    /**
     * Publishes a {@link LineageRelationshipEvent} containing a {@link LineageRelationship}
     *
     * @param lineageRelationship the LineageRelationship to be published
     * @param eventType           the type on the event
     *
     * @throws ConnectorCheckedException unable to send the event due to connectivity issue
     * @throws JsonProcessingException   exception parsing the event json
     */
    public void publishLineageRelationshipEvent(LineageRelationship lineageRelationship,
                                                AssetLineageEventType eventType) throws ConnectorCheckedException, JsonProcessingException {
        LineageRelationshipEvent event = new LineageRelationshipEvent();

        event.setLineageRelationship(lineageRelationship);
        event.setAssetLineageEventType(eventType);

        publishEvent(event);
    }


    /**
     * Publishes a {@link LineageRelationshipsEvent} containing a {@link LineageRelationship}
     *
     * @param assetContext the LineageRelationship to be published
     *
     * @throws ConnectorCheckedException unable to send the event due to connectivity issue
     * @throws JsonProcessingException   exception parsing the event json
     */
    public void publishAssetContextEvent(RelationshipsContext assetContext) throws ConnectorCheckedException, JsonProcessingException {
        LineageRelationshipsEvent event = new LineageRelationshipsEvent();

        event.setRelationshipsContext(assetContext);
        event.setAssetLineageEventType(AssetLineageEventType.ASSET_CONTEXT_EVENT);

        publishEvent(event);
    }

    /**
     * Output a new asset event.
     *
     * @param event event to send
     *
     * @throws ConnectorCheckedException unable to send the event due to connectivity issue
     * @throws JsonProcessingException   exception parsing the event json
     */
    public void publishEvent(AssetLineageEventHeader event) throws JsonProcessingException, ConnectorCheckedException {
        if (outTopicConnector == null)
            return;

        ObjectMapper objectMapper = new ObjectMapper();
        outTopicConnector.sendEvent(objectMapper.writeValueAsString(event));

    }

    /**
     * Publish lineage entity event
     *
     * @param lineageEntity    - lineage entity
     * @param lineageEventType - lineage event type
     *
     * @throws ConnectorCheckedException unable to send the event due to connectivity issue
     * @throws JsonProcessingException   exception parsing the event json
     */
    public void publishLineageEntityEvent(LineageEntity lineageEntity,
                                          AssetLineageEventType lineageEventType) throws ConnectorCheckedException, JsonProcessingException {
        LineageEntityEvent event = new LineageEntityEvent();

        event.setLineageEntity(lineageEntity);
        event.setAssetLineageEventType(lineageEventType);

        publishEvent(event);
    }

    /**
     * Checks if the entity is eligible to be send in a lineage event.
     * The GlossaryTerm and GlossaryCategory entities are send out if has SemanticAssignment and/or TermCategorization relationships
     *
     * @param entityDetail entity to be checked
     *
     * @return true if the entity has the lineage relationships created
     *
     * @throws UserNotAuthorizedException security access problem
     * @throws PropertyServerException    problem accessing property server
     */
    public boolean isEntityEligibleForPublishing(EntityDetail entityDetail) throws OCFCheckedExceptionBase {
        String typeDefName = entityDetail.getType().getTypeDefName();
        if (typeDefName.equals(GLOSSARY_CATEGORY) || typeDefName.equals(GLOSSARY_TERM)) {
            return glossaryHandler.hasGlossaryTermLineageRelationships(serverUserName, entityDetail);
        }

        return true;
    }


}

