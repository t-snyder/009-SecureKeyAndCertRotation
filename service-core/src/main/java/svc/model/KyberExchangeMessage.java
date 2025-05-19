package svc.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Transport object for kyber key exchange and key rotation
 */
public class KyberExchangeMessage
{
  private String svcId         = null; // Client service id that exchange request is going to.
  private String eventType     = null;
  private byte[] publicKey     = null;
  private byte[] encapsulation = null;

  public KyberExchangeMessage( String svcId, String eventType, byte[] publicKey )
  {
    this.svcId     = svcId;        // Client service id
    this.eventType = eventType;
    this.publicKey = publicKey;
  }

  public KyberExchangeMessage( String svcId, String eventType, byte[] publicKey, byte[] encapsulation )
  {
    this.svcId         = svcId;        // Client service id
    this.eventType     = eventType;
    this.publicKey     = publicKey;
    this.encapsulation = encapsulation;
  }

  public String getSvcId()         { return svcId;         }
  public String getEventType()     { return eventType;     }
  public byte[] getPublicKey()     { return publicKey;     }
  public byte[] getEncapsulation() { return encapsulation; }
  
  public static byte[] serialize( KyberExchangeMessage msgObj ) 
   throws IOException
  {
    try( ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
         ObjectOutputStream    objectOutputStream    = new ObjectOutputStream( byteArrayOutputStream )) 
    {
      objectOutputStream.writeObject( msgObj );
      return byteArrayOutputStream.toByteArray();
    }
  }
  
  public static KyberExchangeMessage deserialize( byte[] data ) 
   throws IOException, ClassNotFoundException
  {
    try( ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(data);
         ObjectInputStream    objectInputStream    = new ObjectInputStream(byteArrayInputStream) ) 
    {
      return (KyberExchangeMessage) objectInputStream.readObject();
    }
  }
}
