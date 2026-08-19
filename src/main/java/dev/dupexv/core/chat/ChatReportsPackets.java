package dev.dupexv.core.chat;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.chat.ChatType;
import com.github.retrooper.packetevents.protocol.chat.ChatTypeDecoration;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage;
import com.github.retrooper.packetevents.protocol.chat.message.ChatMessage_v1_19_3;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChatMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDisguisedChat;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerServerData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSystemChatMessage;
import net.kyori.adventure.text.Component;

final class ChatReportsPackets extends PacketListenerAbstract {

    private final ChatReportsService service;

    ChatReportsPackets(ChatReportsService service) {
        super(PacketListenerPriority.LOWEST);
        this.service = service;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.isCancelled()) {
            return;
        }
        var type = event.getPacketType();
        if (type == PacketType.Play.Server.CHAT_MESSAGE) {
            convertPlayerChat(event);
            return;
        }
        if (type == PacketType.Play.Server.DISGUISED_CHAT) {
            convertDisguisedChat(event);
            return;
        }
        if (type == PacketType.Play.Server.JOIN_GAME) {
            spoofJoinGame(event);
            return;
        }
        if (type == PacketType.Play.Server.SERVER_DATA) {
            spoofServerData(event);
            return;
        }
        if (type == PacketType.Play.Server.PLAYER_CHAT_HEADER
                || type == PacketType.Play.Server.DELETE_CHAT) {
            event.setCancelled(true);
        }
    }

    private void convertPlayerChat(PacketSendEvent event) {
        try {
            WrapperPlayServerChatMessage wrapper = new WrapperPlayServerChatMessage(event);
            ChatMessage message = wrapper.getMessage();
            Component display = resolveDisplay(message);
            event.setCancelled(true);
            if (display == null) {
                return;
            }
            event.getUser().sendPacket(new WrapperPlayServerSystemChatMessage(false, display));
        } catch (Throwable t) {
            event.setCancelled(true);
        }
    }

    private void convertDisguisedChat(PacketSendEvent event) {
        try {
            WrapperPlayServerDisguisedChat wrapper = new WrapperPlayServerDisguisedChat(event);
            Component content = wrapper.getMessage();
            ChatType.Bound bound = wrapper.getChatType();
            Component display = decorate(content, bound);
            if (display == null) {
                display = content;
            }
            event.setCancelled(true);
            if (display == null) {
                return;
            }
            event.getUser().sendPacket(new WrapperPlayServerSystemChatMessage(false, display));
        } catch (Throwable t) {
            event.setCancelled(true);
        }
    }

    private void spoofJoinGame(PacketSendEvent event) {
        if (!service.hidePopup()) {
            return;
        }
        try {
            if (event.getServerVersion().isOlderThan(ServerVersion.V_1_20_5)) {
                return;
            }
            WrapperPlayServerJoinGame wrapper = new WrapperPlayServerJoinGame(event);
            if (!wrapper.isEnforcesSecureChat()) {
                wrapper.setEnforcesSecureChat(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private void spoofServerData(PacketSendEvent event) {
        if (!service.hidePopup()) {
            return;
        }
        try {
            WrapperPlayServerServerData wrapper = new WrapperPlayServerServerData(event);
            if (!wrapper.isEnforceSecureChat()) {
                wrapper.setEnforceSecureChat(true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Component resolveDisplay(ChatMessage message) {
        if (message instanceof ChatMessage_v1_19_3 modern) {
            Component unsigned = modern.getUnsignedChatContent().orElse(null);
            if (unsigned != null) {
                return unsigned;
            }
            return decorate(modern.getChatContent(), modern.getChatFormatting());
        }
        return message.getChatContent();
    }

    private static Component decorate(Component content, ChatType.Bound bound) {
        if (content == null) {
            return null;
        }
        if (bound == null || bound.getType() == null) {
            return content;
        }
        ChatTypeDecoration decoration = bound.getType().getChatDecoration();
        if (decoration == null) {
            return content;
        }
        try {
            return decoration.decorate(content, bound);
        } catch (Throwable t) {
            return content;
        }
    }
}
