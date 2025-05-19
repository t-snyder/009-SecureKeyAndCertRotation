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


public class PulsarPublicClient
{
  private static final Logger LOGGER = LoggerFactory.getLogger( PulsarPublicClient.class );
 
  private String       pulsarUrls   = null;
  private PulsarClient pulsarClient = null;
  
  public PulsarPublicClient( Map<String, String> config ) 
   throws PulsarClientException, NoSuchAlgorithmException, NoSuchProviderException, InvalidAlgorithmParameterException
  {
    this.pulsarUrls = config.get( MetadataSvcIF.PulsarUrls );

    pulsarClientInit();

    LOGGER.info( "Pulsar client is initialized." );
  }
  
  private void pulsarClientInit()  
   throws PulsarClientException
  {
    try
    {
      buildClient();
    } 
    catch( PulsarClientException e )
    {
      String msg = "Error building pulsar Client. Error = " + e.getMessage();
      LOGGER.error( msg );
      throw e;
    }

    LOGGER.info( "Pulsar client is ready." );
  }
  
  private void buildClient() 
   throws PulsarClientException
  {
    pulsarClient = PulsarClient.builder().serviceUrl( pulsarUrls ).build();
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