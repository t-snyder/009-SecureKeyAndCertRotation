package svc.utils;


import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import svc.model.KyberExchangeMessage;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class AvroSchemaReader
{
  private static final Logger              LOGGER      = LoggerFactory.getLogger( AvroSchemaReader.class );
  private static       Map<String, Schema> schemas     = new HashMap<>();
  private static final String              SCHEMA_PATH = "/app/avro-schemas";
  
  public static void loadAllSchemas() 
   throws IOException 
  {
    File   schemaDir   = new File( SCHEMA_PATH );
    File[] schemaFiles = schemaDir.listFiles((dir, name) -> name.endsWith(".avsc"));

    LOGGER.info( "AvroSchemaReader.loadAllSchemas - number of schemas found = " + schemaDir.list() ); 
    
    if( schemaFiles != null ) 
    {
      for( File file : schemaFiles ) 
      {
        String schemaName = file.getName().replace(".avsc", "");
        Schema schema     = new Schema.Parser().parse(file);

        schemas.put(schemaName, schema);
      }
    }
  }

  public static Schema getSchema( String schemaName )
   throws Exception
  {
    if( schemas.isEmpty() )
      loadAllSchemas();
    
    return schemas.get( schemaName );
  }
}