/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
/**
 *
 */
package org.apache.geode.internal.cache.tier.sockets.command;

import java.io.IOException;

import org.apache.geode.cache.DynamicRegionFactory;
import org.apache.geode.cache.operations.UnregisterInterestOperationContext;
import org.apache.geode.i18n.StringId;
import org.apache.geode.internal.cache.tier.Command;
import org.apache.geode.internal.cache.tier.InterestType;
import org.apache.geode.internal.cache.tier.MessageType;
import org.apache.geode.internal.cache.tier.sockets.BaseCommand;
import org.apache.geode.internal.cache.tier.sockets.Message;
import org.apache.geode.internal.cache.tier.sockets.Part;
import org.apache.geode.internal.cache.tier.sockets.ServerConnection;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.security.AuthorizeRequest;
import org.apache.geode.security.NotAuthorizedException;


public class UnregisterInterest extends BaseCommand {

  private final static UnregisterInterest singleton = new UnregisterInterest();

  public static Command getCommand() {
    return singleton;
  }

  UnregisterInterest() {}

  @Override
  public void cmdExecute(Message clientMessage, ServerConnection serverConnection, long start)
      throws ClassNotFoundException, IOException {
    Part regionNamePart = null, keyPart = null;
    String regionName = null;
    Object key = null;
    int interestType = 0;
    StringId errMessage = null;
    serverConnection.setAsTrue(REQUIRES_RESPONSE);

    regionNamePart = clientMessage.getPart(0);
    interestType = clientMessage.getPart(1).getInt();
    keyPart = clientMessage.getPart(2);
    Part isClosingPart = clientMessage.getPart(3);
    byte[] isClosingPartBytes = (byte[]) isClosingPart.getObject();
    boolean isClosing = isClosingPartBytes[0] == 0x01;
    regionName = regionNamePart.getString();
    try {
      key = keyPart.getStringOrObject();
    } catch (Exception e) {
      writeException(clientMessage, e, false, serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }
    boolean keepalive = false;
    try {
      Part keepalivePart = clientMessage.getPart(4);
      byte[] keepaliveBytes = (byte[]) keepalivePart.getObject();
      keepalive = keepaliveBytes[0] != 0x00;
    } catch (Exception e) {
      writeException(clientMessage, e, false, serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }
    if (logger.isDebugEnabled()) {
      logger.debug(
          "{}: Received unregister interest request ({} bytes) from {} for region {} key {}",
          serverConnection.getName(), clientMessage.getPayloadLength(),
          serverConnection.getSocketString(), regionName, key);
    }

    // Process the unregister interest request
    if ((key == null) && (regionName == null)) {
      errMessage =
          LocalizedStrings.UnRegisterInterest_THE_INPUT_REGION_NAME_AND_KEY_FOR_THE_UNREGISTER_INTEREST_REQUEST_ARE_NULL;
    } else if (key == null) {
      errMessage =
          LocalizedStrings.UnRegisterInterest_THE_INPUT_KEY_FOR_THE_UNREGISTER_INTEREST_REQUEST_IS_NULL;
    } else if (regionName == null) {
      errMessage =
          LocalizedStrings.UnRegisterInterest_THE_INPUT_REGION_NAME_FOR_THE_UNREGISTER_INTEREST_REQUEST_IS_NULL;
      String s = errMessage.toLocalizedString();
      logger.warn("{}: {}", serverConnection.getName(), s);
      writeErrorResponse(clientMessage, MessageType.UNREGISTER_INTEREST_DATA_ERROR, s,
          serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }

    try {
      if (interestType == InterestType.REGULAR_EXPRESSION) {
        this.securityService.authorizeRegionRead(regionName);
      } else {
        this.securityService.authorizeRegionRead(regionName, key.toString());
      }
    } catch (NotAuthorizedException ex) {
      writeException(clientMessage, ex, false, serverConnection);
      serverConnection.setAsTrue(RESPONDED);
      return;
    }

    AuthorizeRequest authzRequest = serverConnection.getAuthzRequest();
    if (authzRequest != null) {
      if (!DynamicRegionFactory.regionIsDynamicRegionList(regionName)) {
        try {
          UnregisterInterestOperationContext unregisterContext =
              authzRequest.unregisterInterestAuthorize(regionName, key, interestType);
          key = unregisterContext.getKey();
        } catch (NotAuthorizedException ex) {
          writeException(clientMessage, ex, false, serverConnection);
          serverConnection.setAsTrue(RESPONDED);
          return;
        }
      }
    }
    // Yogesh : bug fix for 36457 :
    /*
     * Region destroy message from server to client results in client calling unregister to server
     * (an unnecessary callback). The unregister encounters an error because the region has been
     * destroyed on the server and hence falsely marks the server dead.
     */
    /*
     * Region region = crHelper.getRegion(regionName); if (region == null) {
     * logger.warning(this.name + ": Region named " + regionName + " was not found during unregister
     * interest request"); writeErrorResponse(msg, MessageType.UNREGISTER_INTEREST_DATA_ERROR);
     * responded = true; } else {
     */
    // Unregister interest irrelevent of whether the region is present it or
    // not
    serverConnection.getAcceptor().getCacheClientNotifier().unregisterClientInterest(regionName,
        key, interestType, isClosing, serverConnection.getProxyID(), keepalive);

    // Update the statistics and write the reply
    // bserverStats.incLong(processDestroyTimeId,
    // DistributionStats.getStatTime() - start);
    // start = DistributionStats.getStatTime();
    writeReply(clientMessage, serverConnection);
    serverConnection.setAsTrue(RESPONDED);
    if (logger.isDebugEnabled()) {
      logger.debug("{}: Sent unregister interest response for region {} key {}",
          serverConnection.getName(), regionName, key);
    }
    // bserverStats.incLong(writeDestroyResponseTimeId,
    // DistributionStats.getStatTime() - start);
    // bserverStats.incInt(destroyResponsesId, 1);
    // }
  }

}
