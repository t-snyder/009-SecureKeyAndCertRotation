package controller;


import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;

import java.util.Map;

import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import model.MetadataSvcIF;


public class PulsarTLSClient
{
  private static final Logger LOGGER = LoggerFactory.getLogger( PulsarTLSClient.class );
 
  // Config Parameters
  private boolean pulsarUseTLS     = true;
  private String  pulsarUrls       = null;
  private String  pulsarCaCertPath = null;
  private String  clientSecretName = null;
  // Runtime vars
  private PulsarClient pulsarClient = null;
  
  public PulsarTLSClient( Map<String, String> config ) 
   throws PulsarClientException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    if( config == null || config.size() == 0 )
    {
      String msg = "Config can not be null or empty.";
      LOGGER.error( msg );
      throw new IllegalArgumentException( msg );
    }
    
    this.pulsarUseTLS     = Boolean.getBoolean( config.get( MetadataSvcIF.PulsarUseTLS ));
    this.pulsarUrls       = config.get( MetadataSvcIF.PulsarUrls );
    this.pulsarCaCertPath = config.get( MetadataSvcIF.PulsarCACertPath);
    this.clientSecretName = config.get( MetadataSvcIF.PulsarClientSecret );

    if( clientSecretName == null || clientSecretName.length() == 0 )
    {
      String msg = "Pulsar Client Secret is not set.";
      LOGGER.error( msg );
      throw new IllegalArgumentException( msg );
    }

    if( pulsarUrls == null || pulsarUrls.length() == 0 )
    {
      String msg = "Pulsar URL is not set.";
      LOGGER.error( msg );
      throw new IllegalArgumentException( msg );
    }
    
    if( pulsarCaCertPath == null || pulsarCaCertPath.length() == 0 )
    {
      String msg = "Pulsar TLS certificate path is not set.";
      LOGGER.error( msg );
      throw new IllegalArgumentException( msg );
    }
    
    if( !pulsarUseTLS )
    {
      String msg = "Pulsar TLS is not enabled.";
      LOGGER.error( msg );
      throw new IllegalArgumentException( msg );
    }
    
    pulsarClientInit();

    LOGGER.info( "Pulsar client is initialized." );
  }
  
  private void pulsarClientInit()  
   throws PulsarClientException
  {
    try
    {
      buildTlsClient();
    } 
    catch( PulsarClientException e )
    {
      String msg = "Error building pulsar Client. Error = " + e.getMessage();
      LOGGER.error( msg );
      throw e;
    }

    LOGGER.info( "Pulsar client is ready." );
  }
  
  private void buildTlsClient() 
   throws PulsarClientException
  {
    pulsarClient = PulsarClient.builder()
                               .serviceUrl( pulsarUrls )
                               .tlsTrustCertsFilePath( pulsarCaCertPath )
                               .enableTlsHostnameVerification( false ) // false by default, in any case
                               .allowTlsInsecureConnection(    false ) // false by default, in any case
                               .build();    
  } 

  public void closePulsarClient()
  {
    try
    {
      pulsarClient.close();   
    } 
    catch( PulsarClientException e )
    {
      // TODO Auto-generated catch block
      e.printStackTrace();
      return;
    }
    
    LOGGER.info( "Pulsar client closed." );
  }
  
  public PulsarClient getPulsarClient()  { return pulsarClient;  }
}