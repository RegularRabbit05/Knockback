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

package com.velocitypowered.proxy.protocol.packet.chat.keyed;

import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.packet.chat.ChatQueue;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.text.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

public class KeyedChatHandler implements
    com.velocitypowered.proxy.protocol.packet.chat.ChatHandler<KeyedPlayerChatPacket> {

  private static final Logger logger = LogManager.getLogger(KeyedChatHandler.class);

  private final VelocityServer server;
  private final ConnectedPlayer player;

  public KeyedChatHandler(VelocityServer server, ConnectedPlayer player) {
    this.server = server;
    this.player = player;
  }

  @Override
  public Class<KeyedPlayerChatPacket> packetClass() {
    return KeyedPlayerChatPacket.class;
  }

  public static void invalidCancel(Logger logger, ConnectedPlayer player) {
    logger.fatal("A plugin tried to cancel a signed chat message."
        + " This is no longer possible in 1.19.1 and newer. "
        + "Disconnecting player " + player.getUsername());
    player.disconnect(Component.text("A proxy plugin caused an illegal protocol state. "
        + "Contact your network administrator."));
  }

  public static void invalidChange(Logger logger, ConnectedPlayer player) {
    logger.fatal("A plugin tried to change a signed chat message. "
        + "This is no longer possible in 1.19.1 and newer. "
        + "Disconnecting player " + player.getUsername());
    player.disconnect(Component.text("A proxy plugin caused an illegal protocol state. "
        + "Contact your network administrator."));
  }

  @Override
  public void handlePlayerChatInternal(KeyedPlayerChatPacket packet) {
    ChatQueue chatQueue = this.player.getChatQueue();
    EventManager eventManager = this.server.getEventManager();
    PlayerChatEvent toSend = new PlayerChatEvent(player, packet.getMessage());
    CompletableFuture<PlayerChatEvent> future = eventManager.fire(toSend);

    chatQueue.queuePacket(
        newLastSeen -> future.thenApply(pme -> {
          PlayerChatEvent.ChatResult chatResult = pme.getResult();
          if (!chatResult.isAllowed()) {
            return null;
          }

          String message = chatResult.getMessage().orElse(packet.getMessage());
          broadcastChat(message);
          return null;
        }).exceptionally((ex) -> {
          logger.error("Exception while handling player chat for {}", player, ex);
          return null;
        }),
        packet.getExpiry(),
        null
    );
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
