package com.omnidude.notification;

import java.util.Map;

/** Um tipo de canal. A configuração já vem descriptografada; os erros nunca podem conter segredos. */
public interface Notifier {

    ChannelType type();

    void send(Map<String, String> config, Message message) throws NotifyException;
}
