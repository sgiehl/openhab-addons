/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.persistence.mongodb.internal;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bson.types.ObjectId;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.items.Item;
import org.openhab.core.items.ItemNotFoundException;
import org.openhab.core.items.ItemRegistry;
import org.openhab.core.library.items.ContactItem;
import org.openhab.core.library.items.DateTimeItem;
import org.openhab.core.library.items.DimmerItem;
import org.openhab.core.library.items.NumberItem;
import org.openhab.core.library.items.RollershutterItem;
import org.openhab.core.library.items.SwitchItem;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.FilterCriteria.Operator;
import org.openhab.core.persistence.FilterCriteria.Ordering;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceItemInfo;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.persistence.strategy.PersistenceStrategy;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.BasicDBObject;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;

/**
 * This is the implementation of the MongoDB {@link PersistenceService}.
 *
 * @author Thorsten Hoeger - Initial contribution
 */
@NonNullByDefault
@Component(service = { PersistenceService.class,
        QueryablePersistenceService.class }, configurationPid = "org.openhab.mongodb", configurationPolicy = ConfigurationPolicy.REQUIRE)
public class MongoDBPersistenceService implements QueryablePersistenceService {

    private static final String FIELD_ID = "_id";
    private static final String FIELD_ITEM = "item";
    private static final String FIELD_REALNAME = "realName";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final String FIELD_VALUE = "value";

    private final Logger logger = LoggerFactory.getLogger(MongoDBPersistenceService.class);

    private @NonNullByDefault({}) String url;
    private @NonNullByDefault({}) String db;
    private @NonNullByDefault({}) String collection;
    private boolean collectionPerItem;

    private boolean initialized = false;

    protected final ItemRegistry itemRegistry;

    private @NonNullByDefault({}) MongoClient cl;
    private @NonNullByDefault({}) DBCollection mongoCollection;

    @Activate
    public MongoDBPersistenceService(final @Reference ItemRegistry itemRegistry) {
        this.itemRegistry = itemRegistry;
    }

    @Activate
    public void activate(final BundleContext bundleContext, final Map<String, Object> config) {
        url = (String) config.get("url");
        logger.debug("MongoDB URL {}", url);
        if (url == null || url.isBlank()) {
            logger.warn("The MongoDB database URL is missing - please configure the mongodb:url parameter.");
            return;
        }
        db = (String) config.get("database");
        logger.debug("MongoDB database {}", db);
        if (db == null || db.isBlank()) {
            logger.warn("The MongoDB database name is missing - please configure the mongodb:database parameter.");
            return;
        }
        collection = (String) config.get("collection");
        logger.debug("MongoDB collection {}", collection);
        if (collection == null || collection.isBlank()) {
            collectionPerItem = false;
        } else {
            collectionPerItem = true;
        }

        disconnectFromDatabase();
        connectToDatabase();

        // connection has been established... initialization completed!
        initialized = true;
    }

    @Deactivate
    public void deactivate(final int reason) {
        logger.debug("MongoDB persistence bundle stopping. Disconnecting from database.");
        disconnectFromDatabase();
    }

    @Override
    public String getId() {
        return "mongodb";
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return "Mongo DB";
    }

    @Override
    public void store(Item item, @Nullable String alias) {
        // Don't log undefined/uninitialized data
        if (item.getState() instanceof UnDefType) {
            return;
        }

        // If we've not initialized the bundle, then return
        if (!initialized) {
            logger.warn("MongoDB not initialized");
            return;
        }

        // Connect to mongodb server if we're not already connected
        if (!isConnected()) {
            connectToDatabase();
        }

        // If we still didn't manage to connect, then return!
        if (!isConnected()) {
            logger.warn(
                    "mongodb: No connection to database. Cannot persist item '{}'! Will retry connecting to database next time.",
                    item);
            return;
        }

        String realName = item.getName();

        // If collection Per Item is active, connect to the item Collection
        if (collectionPerItem) {
            connectToCollection(realName);
        }

        String name = (alias != null) ? alias : realName;
        Object value = this.convertValue(item.getState());

        DBObject obj = new BasicDBObject();
        obj.put(FIELD_ID, new ObjectId());
        obj.put(FIELD_ITEM, name);
        obj.put(FIELD_REALNAME, realName);
        obj.put(FIELD_TIMESTAMP, new Date());
        obj.put(FIELD_VALUE, value);
        this.mongoCollection.save(obj);

        // If collection Per Item is active, disconnect after save.
        if (collectionPerItem) {
            disconnectFromCollection();
        }

        logger.debug("MongoDB save {}={}", name, value);
    }

    private Object convertValue(State state) {
        Object value;
        if (state instanceof PercentType) {
            value = ((PercentType) state).toBigDecimal().doubleValue();
        } else if (state instanceof DateTimeType) {
            value = Date.from(((DateTimeType) state).getZonedDateTime().toInstant());
        } else if (state instanceof DecimalType) {
            value = ((DecimalType) state).toBigDecimal().doubleValue();
        } else {
            value = state.toString();
        }
        return value;
    }

    /**
     * @{inheritDoc
     */
    @Override
    public void store(Item item) {
        store(item, null);
    }

    @Override
    public Set<PersistenceItemInfo> getItemInfo() {
        return Collections.emptySet();
    }

    /**
     * Checks if we have a database connection
     *
     * @return true if connection has been established, false otherwise
     */
    private boolean isConnected() {
        return cl != null;
    }

    /**
     * Connects to the database
     */
    private void connectToDatabase() {
        try {
            logger.debug("Connect MongoDB");
            this.cl = new MongoClient(new MongoClientURI(this.url));
            if (collectionPerItem) {
                mongoCollection = cl.getDB(this.db).getCollection(this.collection);

                BasicDBObject idx = new BasicDBObject();
                idx.append(FIELD_TIMESTAMP, 1).append(FIELD_ITEM, 1);
                this.mongoCollection.createIndex(idx);
            }

            logger.debug("Connect MongoDB ... done");
        } catch (Exception e) {
            logger.error("Failed to connect to database {}", this.url);
            throw new RuntimeException("Cannot connect to database", e);
        }
    }

    /**
     * Connects to the Collection
     */
    private void connectToCollection(String collectionName) {
        try {
            mongoCollection = cl.getDB(this.db).getCollection(collectionName);

            BasicDBObject idx = new BasicDBObject();
            idx.append(FIELD_TIMESTAMP, 1).append(FIELD_ITEM, 1);
            this.mongoCollection.createIndex(idx);
        } catch (Exception e) {
            logger.error("Failed to connect to collection {}", collectionName);
            throw new RuntimeException("Cannot connect to collection", e);
        }
    }

    /**
     * Disconnects from the Collection
     */
    private void disconnectFromCollection() {
        this.mongoCollection = null;
    }

    /**
     * Disconnects from the database
     */
    private void disconnectFromDatabase() {
        this.mongoCollection = null;
        if (this.cl != null) {
            this.cl.close();
        }
        cl = null;
    }

    @Override
    public Iterable<HistoricItem> query(FilterCriteria filter) {
        if (!initialized) {
            return Collections.emptyList();
        }

        if (!isConnected()) {
            connectToDatabase();
        }

        if (!isConnected()) {
            return Collections.emptyList();
        }

        String name = filter.getItemName();

        // If collection Per Item is active, connect to the item Collection
        if (collectionPerItem) {
            connectToCollection(name);
        }
        Item item = getItem(name);

        List<HistoricItem> items = new ArrayList<>();
        DBObject query = new BasicDBObject();
        if (filter.getItemName() != null) {
            query.put(FIELD_ITEM, filter.getItemName());
        }
        if (filter.getState() != null && filter.getOperator() != null) {
            String op = convertOperator(filter.getOperator());
            Object value = convertValue(filter.getState());
            query.put(FIELD_VALUE, new BasicDBObject(op, value));
        }
        if (filter.getBeginDate() != null) {
            query.put(FIELD_TIMESTAMP, new BasicDBObject("$gte", filter.getBeginDate()));
        }
        if (filter.getBeginDate() != null) {
            query.put(FIELD_TIMESTAMP, new BasicDBObject("$lte", filter.getBeginDate()));
        }

        Integer sortDir = (filter.getOrdering() == Ordering.ASCENDING) ? 1 : -1;
        DBCursor cursor = this.mongoCollection.find(query).sort(new BasicDBObject(FIELD_TIMESTAMP, sortDir))
                .skip(filter.getPageNumber() * filter.getPageSize()).limit(filter.getPageSize());

        while (cursor.hasNext()) {
            BasicDBObject obj = (BasicDBObject) cursor.next();

            final State state;
            if (item instanceof NumberItem) {
                state = new DecimalType(obj.getDouble(FIELD_VALUE));
            } else if (item instanceof DimmerItem) {
                state = new PercentType(obj.getInt(FIELD_VALUE));
            } else if (item instanceof SwitchItem) {
                state = OnOffType.valueOf(obj.getString(FIELD_VALUE));
            } else if (item instanceof ContactItem) {
                state = OpenClosedType.valueOf(obj.getString(FIELD_VALUE));
            } else if (item instanceof RollershutterItem) {
                state = new PercentType(obj.getInt(FIELD_VALUE));
            } else if (item instanceof DateTimeItem) {
                state = new DateTimeType(
                        ZonedDateTime.ofInstant(obj.getDate(FIELD_VALUE).toInstant(), ZoneId.systemDefault()));
            } else {
                state = new StringType(obj.getString(FIELD_VALUE));
            }

            items.add(new MongoDBItem(name, state,
                    ZonedDateTime.ofInstant(obj.getDate(FIELD_TIMESTAMP).toInstant(), ZoneId.systemDefault())));
        }

        // If collection Per Item is active, disconnect after save.
        if (collectionPerItem) {
            disconnectFromCollection();
        }
        return items;
    }

    private @Nullable String convertOperator(Operator operator) {
        switch (operator) {
            case EQ:
                return "$eq";
            case GT:
                return "$gt";
            case GTE:
                return "$gte";
            case LT:
                return "$lt";
            case LTE:
                return "$lte";
            case NEQ:
                return "$neq";
            default:
                return null;
        }
    }

    private @Nullable Item getItem(String itemName) {
        try {
            return itemRegistry.getItem(itemName);
        } catch (ItemNotFoundException e1) {
            logger.error("Unable to get item type for {}", itemName);
        }
        return null;
    }

    @Override
    public List<PersistenceStrategy> getDefaultStrategies() {
        return Collections.emptyList();
    }
}
