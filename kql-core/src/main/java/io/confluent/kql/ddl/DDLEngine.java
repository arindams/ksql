package io.confluent.kql.ddl;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.confluent.kql.KQLEngine;
import io.confluent.kql.metastore.DataSource;
import io.confluent.kql.metastore.KQLStream;
import io.confluent.kql.metastore.KQLTable;
import io.confluent.kql.metastore.KQLTopic;
import io.confluent.kql.parser.tree.CreateStream;
import io.confluent.kql.parser.tree.CreateTable;
import io.confluent.kql.parser.tree.CreateTopic;
import io.confluent.kql.parser.tree.DropTable;
import io.confluent.kql.parser.tree.TableElement;
import io.confluent.kql.serde.KQLTopicSerDe;
import io.confluent.kql.serde.avro.KQLAvroTopicSerDe;
import io.confluent.kql.serde.csv.KQLCsvTopicSerDe;
import io.confluent.kql.serde.json.KQLJsonTopicSerDe;
import io.confluent.kql.util.KQLException;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DDLEngine {

  KQLEngine kqlEngine;

  public DDLEngine(KQLEngine kqlEngine) {
    this.kqlEngine = kqlEngine;
  }

  public KQLTopic createTopic(CreateTopic createTopic) {

    String topicName = createTopic.getName().getSuffix().toUpperCase();
    if (kqlEngine.getMetaStore().getTopic(topicName) != null) {
      if (createTopic.isNotExists()) {
        System.out.println("Topic already exists.");
      } else {
        throw new KQLException("Topic already exists.");
      }
      return null;
    }


    if (createTopic.getProperties().size() == 0) {
      throw new KQLException("Create topic statement needs WITH clause.");
    }


    if (createTopic.getProperties().get(DDLConfig.FORMAT_PROPERTY) == null) {
      throw new KQLException("Topic format(format) should be set in WITH clause.");
    }
    String serde = createTopic.getProperties().get(DDLConfig.FORMAT_PROPERTY).toString();
    serde = enforceString(DDLConfig.FORMAT_PROPERTY, serde);


    if (createTopic.getProperties().get(DDLConfig.KAFKA_TOPIC_NAME_PROPERTY) == null) {
      throw new KQLException("Corresponding kafka topic should be set in WITH clause.");
    }
    String kafkaTopicName = createTopic.getProperties().get(DDLConfig.KAFKA_TOPIC_NAME_PROPERTY).toString();
    kafkaTopicName = enforceString(DDLConfig.KAFKA_TOPIC_NAME_PROPERTY,kafkaTopicName);
    KQLTopicSerDe topicSerDe;
    if (serde.equalsIgnoreCase(DataSource.AVRO_SERDE_NAME)) {

      if (createTopic.getProperties().get(DDLConfig.AVRO_SCHEMA_FILE) == null) {
        throw new KQLException("Avro schema file path should be set for avro topics.");
      }
      String avroSchemFile = createTopic.getProperties().get(DDLConfig.AVRO_SCHEMA_FILE).toString();
      avroSchemFile = enforceString(DDLConfig.AVRO_SCHEMA_FILE, avroSchemFile);
      try {
        String avroSchema = getAvroSchema(avroSchemFile);
        topicSerDe = new KQLAvroTopicSerDe(avroSchemFile, avroSchema);
      } catch (IOException e) {
        throw new KQLException("Could not read avro schema from file: " + avroSchemFile);
      }
    } else if (serde.equalsIgnoreCase(DataSource.JSON_SERDE_NAME)) {
      topicSerDe = new KQLJsonTopicSerDe();
    } else if (serde.equalsIgnoreCase(DataSource.CSV_SERDE_NAME)) {
      topicSerDe = new KQLCsvTopicSerDe();
    } else {
      throw new KQLException("The specified topic serde is not supported.");
    }
    KQLTopic kQLTopic = new KQLTopic(topicName, kafkaTopicName, topicSerDe);

    // TODO: Need to check if the topic exists.
    // Add the topic to the metastore
    kqlEngine.getMetaStore().putTopic(kQLTopic);
    return kQLTopic;
  }

  private String enforceString(String propertyName, String propertyValue) {
    if (!propertyValue.startsWith("'") && !propertyValue.endsWith("'")) {
      throw new KQLException(propertyName + " value is string and should be enclosed between "
                             + "\"'\".");
    }
    return propertyValue.substring(1,propertyValue.length()-1);
  }

  public void dropTopic(DropTable dropTable) {

    String topicName = dropTable.getTableName().getSuffix().toUpperCase();
    new DDLUtil().deleteTopic(topicName);
    kqlEngine.getMetaStore().deleteSource(topicName);
  }

  public KQLStream createStream(CreateStream createStream) {

    String streamName = createStream.getName().getSuffix().toUpperCase();
    if (kqlEngine.getMetaStore().getSource(streamName) != null) {
      if (createStream.isNotExists()) {
        System.out.println("Stream already exists.");
      } else {
        throw new KQLException("Stream already exists.");
      }
      return null;
    }

    if (createStream.getElements().size() == 0) {
      throw new KQLException("No column was specified.");
    }

    SchemaBuilder streamSchema = SchemaBuilder.struct();
    for (TableElement tableElement: createStream.getElements()) {
      streamSchema = streamSchema.field(tableElement.getName(), getKQLType(tableElement.getType()));
    }

    if (createStream.getProperties().size() == 0) {
      throw new KQLException("Create stream statement needs WITH clause.");
    }

    if (createStream.getProperties().get(DDLConfig.TOPIC_NAME_PROPERTY) == null) {
      throw new KQLException("Topic for the stream should be set in WITH clause.");
    }

    String topicName = createStream.getProperties().get(DDLConfig.TOPIC_NAME_PROPERTY).toString();
    topicName = enforceString(DDLConfig.TOPIC_NAME_PROPERTY, topicName);

    if (createStream.getProperties().get(DDLConfig.KEY_NAME_PROPERTY) == null) {
      throw new KQLException("Key field name for the stream should be set in WITH clause.");
    }

    String keyName = createStream.getProperties().get(DDLConfig.KEY_NAME_PROPERTY).toString();
    keyName = enforceString(DDLConfig.KEY_NAME_PROPERTY, keyName);

    if (kqlEngine.getMetaStore().getTopic(topicName) == null) {
      throw new KQLException("THe corresponding topic is does not exist.");
    }

    KQLStream kqlStream = new KQLStream(streamName, streamSchema, streamSchema.field(keyName), kqlEngine.getMetaStore().getTopic(topicName));

    // TODO: Need to check if the topic exists.
    // Add the topic to the metastore
    kqlEngine.getMetaStore().putSource(kqlStream);
    return  kqlStream;
  }

  public KQLTable createTable(CreateTable createTable) {

    String tableName = createTable.getName().getSuffix().toUpperCase();
    if (kqlEngine.getMetaStore().getSource(tableName) != null) {
      if (createTable.isNotExists()) {
        System.out.println("Topic already exists.");
      } else {
        throw new KQLException("Topic already exists.");
      }
      return null;
    }

    if (createTable.getElements().size() == 0) {
      throw new KQLException("No column was specified.");
    }

    SchemaBuilder tableSchema = SchemaBuilder.struct();
    for (TableElement tableElement: createTable.getElements()) {
      tableSchema = tableSchema.field(tableElement.getName(), getKQLType(tableElement.getType()));
    }

    if (createTable.getProperties().size() == 0) {
      throw new KQLException("Create table statement needs WITH clause.");
    }

    if (createTable.getProperties().get(DDLConfig.TOPIC_NAME_PROPERTY) == null) {
      throw new KQLException("Topic (topic) for the table should be set in WITH clause.");
    }

    String topicName = createTable.getProperties().get(DDLConfig.TOPIC_NAME_PROPERTY).toString();
    topicName = enforceString(DDLConfig.TOPIC_NAME_PROPERTY, topicName);

    if (createTable.getProperties().get(DDLConfig.STATE_STORE_NAME_PROPERTY) == null) {
      throw new KQLException("State store (statestore) name for the table should be set in WITH clause.");
    }

    String stateStoreName = createTable.getProperties().get(DDLConfig.STATE_STORE_NAME_PROPERTY)
        .toString();
    stateStoreName = enforceString(DDLConfig.STATE_STORE_NAME_PROPERTY, stateStoreName);

    if (createTable.getProperties().get(DDLConfig.KEY_NAME_PROPERTY) == null) {
      throw new KQLException("Key(key) field name for the stream should be set in WITH clause.");
    }

    String keyName = createTable.getProperties().get(DDLConfig.KEY_NAME_PROPERTY).toString();
    keyName = enforceString(DDLConfig.KEY_NAME_PROPERTY, keyName);

    if (kqlEngine.getMetaStore().getTopic(topicName) == null) {
      throw new KQLException("The corresponding topic is does not exist.");
    }

    KQLTable kqlTable = new KQLTable(tableName, tableSchema, tableSchema.field(keyName),
                                     kqlEngine.getMetaStore().getTopic(topicName), stateStoreName);


    // TODO: Need to check if the topic exists.
    // Add the topic to the metastore
    kqlEngine.getMetaStore().putSource(kqlTable);
    return kqlTable;
  }

  //TODO: this needs to be moved to proper place to be accessible to everyone. Temporary!
  private Schema getKQLType(String sqlType) {
    if (sqlType.equalsIgnoreCase("BIGINT") || sqlType.equalsIgnoreCase("LONG")) {
      return Schema.INT64_SCHEMA;
    } else if (sqlType.equalsIgnoreCase("VARCHAR") || sqlType.equalsIgnoreCase("STRING")) {
      return Schema.STRING_SCHEMA;
    } else if (sqlType.equalsIgnoreCase("DOUBLE")) {
      return Schema.FLOAT64_SCHEMA;
    } else if (sqlType.equalsIgnoreCase("INTEGER") || sqlType.equalsIgnoreCase("INT")) {
      return Schema.INT32_SCHEMA;
    } else if (sqlType.equalsIgnoreCase("BOOELAN") || sqlType.equalsIgnoreCase("BOOL")) {
      return Schema.BOOLEAN_SCHEMA;
    }
    throw new KQLException("Unsupported type: " + sqlType);
  }
  private String getAvroSchema(String schemaFilePath) throws IOException {
    byte[] jsonData = Files.readAllBytes(Paths.get(schemaFilePath));
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode root = objectMapper.readTree(jsonData);
    return root.toString();
  }
}
