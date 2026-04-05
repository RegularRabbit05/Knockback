/*
 * Copyright (C) 2022-2023 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet.chat.session;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatAcknowledgementPacket;
import com.velocitypowered.proxy.protocol.packet.chat.ChatHandler;
import com.velocitypowered.proxy.protocol.packet.chat.ChatQueue;
import com.velocitypowered.proxy.protocol.packet.chat.LastSeenMessages;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.concurrent.CompletableFuture;

public class SessionChatHandler implements ChatHandler<SessionPlayerChatPacket> {

  private static final Logger logger = LogManager.getLogger(SessionChatHandler.class);

  private final ConnectedPlayer player;
  private final VelocityServer server;

  public SessionChatHandler(ConnectedPlayer player, VelocityServer server) {
    this.player = player;
    this.server = server;
  }

  @Override
  public Class<SessionPlayerChatPacket> packetClass() {
    return SessionPlayerChatPacket.class;
  }

  @Override
  public void handlePlayerChatInternal(SessionPlayerChatPacket packet) {
    ChatQueue chatQueue = this.player.getChatQueue();
    EventManager eventManager = this.server.getEventManager();
    PlayerChatEvent toSend = new PlayerChatEvent(player, packet.getMessage());
    CompletableFuture<PlayerChatEvent> eventFuture = eventManager.fire(toSend);
    chatQueue.queuePacket(
        newLastSeenMessages -> eventFuture
            .thenApply(pme -> {
              PlayerChatEvent.ChatResult chatResult = pme.getResult();
              if (!chatResult.isAllowed()) {
                return consumeChat(newLastSeenMessages);
              }

              String message = chatResult.getMessage().orElse(packet.getMessage());
              broadcastChat(message);
              return consumeChat(newLastSeenMessages);
            })
            .exceptionally((ex) -> {
              logger.error("Exception while handling player chat for {}", player, ex);
              return null;
            }),
        packet.getTimestamp(),
        packet.getLastSeenMessages()
    );
  }

  private @Nullable MinecraftPacket consumeChat(@Nullable LastSeenMessages lastSeenMessages) {
    if (lastSeenMessages != null) {
      int offset = lastSeenMessages.getOffset();
      if (offset != 0) {
        return new ChatAcknowledgementPacket(offset);
      }
    }
    return null;
  }

  private void broadcastChat(String message) {
    Component chatMessage = Component.translatable("chat.type.text",
        Component.text(player.getUsername()),
        Component.text(message));
    Identity identity = player.identity();

    player.getCurrentServer().ifPresent(serverConnection -> {
      for (Player p : serverConnection.getServer().getPlayersConnected()) {
        p.sendMessage(identity, chatMessage);
      }
    });
  }
}
