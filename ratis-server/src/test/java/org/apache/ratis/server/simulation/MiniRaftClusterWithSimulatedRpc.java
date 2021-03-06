/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ratis.server.simulation;

import org.apache.ratis.MiniRaftCluster;
import org.apache.ratis.client.RaftClientRequestSender;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.server.impl.RaftServerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

public class MiniRaftClusterWithSimulatedRpc extends MiniRaftCluster {
  static final Logger LOG = LoggerFactory.getLogger(MiniRaftClusterWithSimulatedRpc.class);

  public static final Factory<MiniRaftClusterWithSimulatedRpc> FACTORY
      = new Factory<MiniRaftClusterWithSimulatedRpc>() {
    @Override
    public MiniRaftClusterWithSimulatedRpc newCluster(
        String[] ids, RaftProperties prop, boolean formatted) {
      prop.setInt(SimulatedRequestReply.SIMULATE_LATENCY_KEY, 0);
      return new MiniRaftClusterWithSimulatedRpc(ids, prop, formatted);
    }
  };

  private SimulatedRequestReply<RaftServerRequest, RaftServerReply> serverRequestReply;
  private SimulatedClientRequestReply client2serverRequestReply;

  public MiniRaftClusterWithSimulatedRpc(int numServers,
      RaftProperties properties) {
    this(generateIds(numServers, 0), properties, true);
  }

  public MiniRaftClusterWithSimulatedRpc(String[] ids,
      RaftProperties properties, boolean formatted) {
    super(ids, properties, formatted);
    initRpc();
  }

  private void initRpc() {
    final Collection<RaftPeer> peers = getConf().getPeers();
    final int simulateLatencyMs = properties.getInt(
        SimulatedRequestReply.SIMULATE_LATENCY_KEY,
        SimulatedRequestReply.SIMULATE_LATENCY_DEFAULT);
    LOG.info(SimulatedRequestReply.SIMULATE_LATENCY_KEY + " = "
        + simulateLatencyMs);
    serverRequestReply = new SimulatedRequestReply<>(peers, simulateLatencyMs);
    client2serverRequestReply = new SimulatedClientRequestReply(peers,
        simulateLatencyMs);

    setRpcServers(getServers());
  }

  private void setRpcServers(Collection<RaftServerImpl> newServers) {
    newServers.forEach(s -> s.setServerRpc(
        new SimulatedServerRpc(s, serverRequestReply, client2serverRequestReply)));
  }

  @Override
  protected void setPeerRpc() {
    initRpc();
  }

  private void addPeersToRpc(Collection<RaftPeer> peers) {
    serverRequestReply.addPeers(peers);
    client2serverRequestReply.addPeers(peers);
  }

  @Override
  public void restartServer(String id, boolean format) throws IOException {
    super.restartServer(id, format);
    RaftServerImpl s = getServer(id);
    addPeersToRpc(Collections.singletonList(conf.getPeer(id)));
    s.setServerRpc(new SimulatedServerRpc(s, serverRequestReply,
        client2serverRequestReply));
    s.start();
  }

  @Override
  public Collection<RaftPeer> addNewPeers(Collection<RaftPeer> newPeers,
                                          Collection<RaftServerImpl> newServers, boolean startService) {
    addPeersToRpc(newPeers);
    setRpcServers(newServers);
    if (startService) {
      newServers.forEach(RaftServerImpl::start);
    }
    return newPeers;
  }

  @Override
  public RaftClientRequestSender getRaftClientRequestSender() {
    return client2serverRequestReply;
  }

  @Override
  public void blockQueueAndSetDelay(String leaderId, int delayMs)
      throws InterruptedException {
    // block leader sendRequest if delayMs > 0
    final boolean block = delayMs > 0;
    LOG.debug("{} leader queue {} and set {}ms delay for the other queues",
        block? "Block": "Unblock", leaderId, delayMs);
    serverRequestReply.getQueue(leaderId).blockSendRequestTo.set(block);

    // set delay takeRequest for the other queues
    getServers().stream().filter(s -> !s.getId().equals(leaderId))
        .map(s -> serverRequestReply.getQueue(s.getId()))
        .forEach(q -> q.delayTakeRequestTo.set(delayMs));

    final long sleepMs = 3 * getMaxTimeout() / 2;
    Thread.sleep(sleepMs);
  }

  @Override
  public void setBlockRequestsFrom(String src, boolean block) {
    serverRequestReply.getQueue(src).blockTakeRequestFrom.set(block);
  }
}
