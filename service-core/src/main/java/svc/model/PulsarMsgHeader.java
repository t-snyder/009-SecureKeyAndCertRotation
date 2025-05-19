package svc.model;

import java.util.HashMap;
import java.util.Map;


public record PulsarMsgHeader( String serviceId, String eventType, String correlation_id, String timeStamp, String version )
{

  public Map<String, String> toMap()
  {
    Map<String, String> props = new HashMap<String, String>();
    
    if( !serviceId.isBlank()      ) props.put( ServiceCoreIF.MsgHeaderServiceID,     serviceId      );
    if( !eventType.isBlank()      ) props.put( ServiceCoreIF.MsgHeaderEventType,     eventType      );
    if( !correlation_id.isBlank() ) props.put( ServiceCoreIF.MsgHeaderCorrelationId, correlation_id );
    if( !timeStamp.isBlank()      ) props.put( ServiceCoreIF.MsgHeaderTimeStamp,     timeStamp      );
    if( !version.isBlank()        ) props.put( ServiceCoreIF.MsgHeaderVersion,       version        );

    return props;
  }
}
