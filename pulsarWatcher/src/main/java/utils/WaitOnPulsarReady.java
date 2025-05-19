package utils;


import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;

import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.concurrent.TimeUnit;


public class WaitOnPulsarReady
{
  private static KubernetesClient kubernetesClient = null;
//  private static String           nameSpace        = null;
//  private static String           podName          = null;
  
  public static boolean waitOnPulsar( KubernetesClient client, String nameSpace, String podName )
  {
    // Create a Fabric8 Kubernetes client. The client will read in-cluster configuration
    kubernetesClient = client;
    
    // Wait for the pod to become ready with a timeout of 5 minutes
    Pod pod = kubernetesClient.pods().inNamespace( nameSpace )
                                     .withName(    podName   )
                                     .waitUntilCondition( p -> isPodReady( p ), 5, TimeUnit.MINUTES );

    if( pod != null && isPodReady( pod ) )
    {
      System.out.println( "Pulsar Pod is ready!" );
      return true;
    }
    else
    {
      System.out.println( "Pulsar Pod is not ready after waiting." );
      return false;
    }
  }

  private static boolean isPodReady( Pod pod )
  {
    if( pod == null || pod.getStatus()  == null ) return false;
    if( pod.getStatus().getConditions() == null ) return false;

    for( PodCondition condition : pod.getStatus().getConditions() )
    {
      if( "Ready".equals( condition.getType() ) && "True".equalsIgnoreCase( condition.getStatus() ) )
      {
        return true;
      }
    }
    return false;
  }
}
