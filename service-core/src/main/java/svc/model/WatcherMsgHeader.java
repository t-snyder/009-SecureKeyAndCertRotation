package svc.model;

import java.util.HashMap;
import java.util.Map;

import io.vertx.core.json.JsonObject;


public record WatcherMsgHeader( String genId, String eventType, String correlationId, String timeStamp, String version )
{

  public Map<String, String> toMap()
  {
    Map<String, String> props = new HashMap<String, String>();
    
    if( genId         != null && !genId.isBlank()         ) props.put( WatcherIF.WatcherHeaderGenID,         genId         );
    if( eventType     != null && !eventType.isBlank()     ) props.put( WatcherIF.WatcherHeaderEventType,     eventType     );
    if( correlationId != null && !correlationId.isBlank() ) props.put( WatcherIF.WatcherHeaderCorrelationId, correlationId );
    if( timeStamp     != null && !timeStamp.isBlank()     ) props.put( WatcherIF.WatcherHeaderTimeStamp,     timeStamp     );
    if( version       != null && !version.isBlank()       ) props.put( WatcherIF.WatcherHeaderVersion,       version       );

    return props;
  }
  
  public JsonObject toJson()
  {
    JsonObject props = new JsonObject();

    if( genId         != null && !genId.isBlank()         ) props.put( WatcherIF.WatcherHeaderGenID,         genId         );
    if( eventType     != null && !eventType.isBlank()     ) props.put( WatcherIF.WatcherHeaderEventType,     eventType     );
    if( correlationId != null && !correlationId.isBlank() ) props.put( WatcherIF.WatcherHeaderCorrelationId, correlationId );
    if( timeStamp     != null && !timeStamp.isBlank()     ) props.put( WatcherIF.WatcherHeaderTimeStamp,     timeStamp     );
    if( version       != null && !version.isBlank()       ) props.put( WatcherIF.WatcherHeaderVersion,       version       );

    return props;
  }
  
  public static Map<String, String> fromJson( JsonObject json )
  {
    Map<String, String> props = new HashMap<String, String>();

    if( json.containsKey( WatcherIF.WatcherHeaderGenID         )) props.put( WatcherIF.WatcherHeaderGenID,         json.getString( WatcherIF.WatcherHeaderGenID         ));
    if( json.containsKey( WatcherIF.WatcherHeaderEventType     )) props.put( WatcherIF.WatcherHeaderEventType,     json.getString( WatcherIF.WatcherHeaderEventType     ));
    if( json.containsKey( WatcherIF.WatcherHeaderCorrelationId )) props.put( WatcherIF.WatcherHeaderCorrelationId, json.getString( WatcherIF.WatcherHeaderCorrelationId ));
    if( json.containsKey( WatcherIF.WatcherHeaderTimeStamp     )) props.put( WatcherIF.WatcherHeaderTimeStamp,     json.getString( WatcherIF.WatcherHeaderTimeStamp     ));
    if( json.containsKey( WatcherIF.WatcherHeaderVersion       )) props.put( WatcherIF.WatcherHeaderVersion,       json.getString( WatcherIF.WatcherHeaderVersion       ));

    return props;
  }
}
