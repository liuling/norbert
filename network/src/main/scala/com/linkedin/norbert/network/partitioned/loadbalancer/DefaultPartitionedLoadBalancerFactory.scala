/*
 * Copyright 2009-2010 LinkedIn, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.linkedin.norbert
package network
package partitioned
package loadbalancer

import cluster.{Node, InvalidClusterException}
import common.Endpoint
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/**
 * This class is intended for applications where there is a mapping from partitions -> servers able to respond to those requests. Requests are round-robined
 * between the partitions
 */
abstract class DefaultPartitionedLoadBalancerFactory[PartitionedId](numPartitions: Int, serveRequestsIfPartitionMissing: Boolean = true) extends PartitionedLoadBalancerFactory[PartitionedId] {
  def newLoadBalancer(endpoints: Set[Endpoint]): PartitionedLoadBalancer[PartitionedId] = new PartitionedLoadBalancer[PartitionedId] with DefaultLoadBalancerHelper {
    val partitionToNodeMap = generatePartitionToNodeMap(endpoints, numPartitions, serveRequestsIfPartitionMissing)

    def nextNode(id: PartitionedId, capability: Option[Long] = None) = nodeForPartition(partitionForId(id), capability)


    def nodesForPartitionedId(id: PartitionedId, capability: Option[Long] = None) = {
      partitionToNodeMap.getOrElse(partitionForId(id), (Vector.empty[Endpoint], new AtomicInteger(0), new Array[AtomicBoolean](0)))._1.filter(_.node.isCapableOf(capability)).toSet.map
      { (endpoint: Endpoint) => endpoint.node }
    }

    def nodesForOneReplica(id: PartitionedId, capability: Option[Long] = None) = {
      nodesForPartitions(id, partitionToNodeMap, capability)
    }

    def nodesForPartitions(id: PartitionedId, partitions: Set[Int], capability: Option[Long] = None) = {
      nodesForPartitions(id, partitionToNodeMap.filterKeys(partitions contains _), capability)
    }

    def nodesForPartitions(id: PartitionedId, partitionToNodeMap: Map[Int, (IndexedSeq[Endpoint], AtomicInteger, Array[AtomicBoolean])], capability: Option[Long]) = {
      partitionToNodeMap.keys.foldLeft(Map.empty[Node, Set[Int]]) { (map, partition) =>
        val nodeOption = nodeForPartition(partition, capability)
        if(nodeOption.isDefined) {
          val n = nodeOption.get
          map + (n -> (map.getOrElse(n, Set.empty[Int]) + partition))
        } else if(serveRequestsIfPartitionMissing) {
          log.warn("Partition %s is unavailable, attempting to continue serving requests to other partitions.".format(partition))
          map
        } else
          throw new InvalidClusterException("Partition %s is unavailable, cannot serve requests.".format(partition))
      }
    }
  }
  /**
   * Calculates the id of the partition on which the specified <code>Id</code> resides.
   *
   * @param id the <code>Id</code> to map to a partition
   *
   * @return the id of the partition on which the <code>Idrever</code> resides
   */
  def partitionForId(id: PartitionedId): Int = {
    calculateHash(id).abs % numPartitions
  }

  /**
   * Hashes the <code>Id</code> provided. Users must implement this method. The <code>HashFunctions</code>
   * object provides an implementation of the FNV hash which may help in the implementation.
   *
   * @param id the <code>Id</code> to hash
   *
   * @return the hashed value
   */
  protected def calculateHash(id: PartitionedId): Int

  def getNumPartitions(endpoints: Set[Endpoint]): Int
}
