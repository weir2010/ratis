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
package org.apache.raft.grpc.server;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.apache.raft.grpc.proto.RaftServerProtocolServiceGrpc;
import org.apache.raft.grpc.proto.RaftServerProtocolServiceGrpc.RaftServerProtocolServiceBlockingStub;
import org.apache.raft.grpc.proto.RaftServerProtocolServiceGrpc.RaftServerProtocolServiceStub;
import org.apache.raft.proto.RaftProtos.RequestVoteReplyProto;
import org.apache.raft.proto.RaftProtos.RequestVoteRequestProto;
import org.apache.raft.protocol.RaftPeer;
import org.apache.raft.server.protocol.RequestVoteReply;
import org.apache.raft.server.protocol.RequestVoteRequest;
import org.apache.raft.server.protocol.ServerProtoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is a RaftClient implementation that supports streaming data to the raft
 * ring. The stream implementation utilizes gRPC.
 */
public class RaftServerProtocolClient {
  private static final Logger LOG = LoggerFactory.getLogger(RaftServerProtocolClient.class);

  private final ManagedChannel channel;
  private final RaftServerProtocolServiceBlockingStub blockingStub;
  private final RaftServerProtocolServiceStub asyncStub;

  public RaftServerProtocolClient(RaftPeer target) {
    channel = ManagedChannelBuilder.forTarget(target.getAddress())
        .usePlaintext(true).build();
    blockingStub = RaftServerProtocolServiceGrpc.newBlockingStub(channel);
    asyncStub = RaftServerProtocolServiceGrpc.newStub(channel);
  }

  public void shutdown() {
    channel.shutdownNow();
  }

  public RequestVoteReply requestVote(RequestVoteRequest request) {
    RequestVoteRequestProto requestProto = ServerProtoUtils
        .toRequestVoteRequestProto(request);
    try {
      RequestVoteReplyProto replyProto = blockingStub.requestVote(requestProto);
      return ServerProtoUtils.toRequestVoteReply(replyProto);
    } catch (StatusRuntimeException e) {
      LOG.warn("RequestVote RPC failed", e);
      return null;
    }
  }
}