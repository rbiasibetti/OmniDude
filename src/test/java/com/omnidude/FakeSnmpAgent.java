package com.omnidude;

import java.io.IOException;
import java.util.Map;
import java.util.NavigableMap;
import org.snmp4j.CommandResponder;
import org.snmp4j.CommandResponderEvent;
import org.snmp4j.MessageDispatcherImpl;
import org.snmp4j.MessageException;
import org.snmp4j.PDU;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.mp.MPv1;
import org.snmp4j.mp.MPv2c;
import org.snmp4j.mp.MPv3;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.OctetString;
import org.snmp4j.mp.StatusInformation;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.TimeTicks;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

/**
 * Agente SNMP em processo (v1/v2c, ou v3 com um usuário USM), só para a credencial correta (como um agente real:
 * community errada não responde).
 * Sem MIB, responde TimeTicks a qualquer GET (modo simples das sondas); com MIB, implementa GET, GETNEXT e GETBULK.
 */
public class FakeSnmpAgent implements AutoCloseable {

    private final String community;
    private final NavigableMap<OID, Variable> mib;
    private final Snmp snmp;
    private final int port;

    public FakeSnmpAgent(String community) throws IOException {
        this(community, "127.0.0.1");
    }

    public FakeSnmpAgent(String community, String bindIp) throws IOException {
        this(community, bindIp, 0, null);
    }

    public FakeSnmpAgent(String community, String bindIp, int bindPort, NavigableMap<OID, Variable> mib)
            throws IOException {
        this(community, bindIp, bindPort, mib, null);
    }

    /** Agente SNMPv3: só responde ao usuário informado, no nível de segurança que o {@code user} implica. */
    public static FakeSnmpAgent v3(UsmUser user, NavigableMap<OID, Variable> mib) throws IOException {
        return v3(user, mib, "127.0.0.1");
    }

    public static FakeSnmpAgent v3(UsmUser user, NavigableMap<OID, Variable> mib, String bindIp) throws IOException {
        return new FakeSnmpAgent(user.getSecurityName().toString(), bindIp, 0, mib, user);
    }

    private FakeSnmpAgent(String community, String bindIp, int bindPort, NavigableMap<OID, Variable> mib,
                          UsmUser v3User) throws IOException {
        this.community = community;
        this.mib = mib;
        DefaultUdpTransportMapping transport = new DefaultUdpTransportMapping(new UdpAddress(bindIp + "/" + bindPort));
        MessageDispatcherImpl dispatcher = new MessageDispatcherImpl();
        USM usm = null;
        if (v3User != null) {
            usm = new USM(com.omnidude.snmp.SnmpClient.securityProtocols(), new OctetString(MPv3.createLocalEngineID()), 0);
            dispatcher.addMessageProcessingModel(new MPv3(usm));
        } else {
            dispatcher.addMessageProcessingModel(new MPv1());
            dispatcher.addMessageProcessingModel(new MPv2c());
        }
        snmp = new Snmp(dispatcher, transport);
        if (usm != null) {
            snmp.getUSM().addUser(v3User);
        }
        snmp.addCommandResponder((CommandResponder) this::respond);
        snmp.listen();
        port = ((UdpAddress) transport.getListenAddress()).getPort();
    }

    public int port() {
        return port;
    }

    private void respond(CommandResponderEvent<?> event) {
        PDU request = event.getPDU();
        if (request == null || !community.equals(new String(event.getSecurityName()))) {
            return;                                           // community errada: agentes reais não respondem
        }
        PDU response = mib == null ? simple(request) : answer(request, event.getMessageProcessingModel() == MPv1.ID);
        if (request instanceof ScopedPDU in) {                      // SNMPv3: a resposta precisa do mesmo contexto
            ScopedPDU out = new ScopedPDU();
            out.setContextEngineID(in.getContextEngineID());
            out.setContextName(in.getContextName());
            out.setType(PDU.RESPONSE);
            out.setErrorStatus(response.getErrorStatus());
            out.setErrorIndex(response.getErrorIndex());
            out.addAll(response.getVariableBindings());
            response = out;
        }
        response.setRequestID(request.getRequestID());
        event.setProcessed(true);
        try {
            snmp.getMessageDispatcher().returnResponsePdu(event.getMessageProcessingModel(), event.getSecurityModel(),
                    event.getSecurityName(), event.getSecurityLevel(), response, event.getMaxSizeResponsePDU(),
                    event.getStateReference(), new StatusInformation());
        } catch (MessageException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PDU simple(PDU request) {
        PDU response = new PDU();
        response.setType(PDU.RESPONSE);
        for (VariableBinding vb : request.getVariableBindings()) {
            response.add(new VariableBinding(vb.getOid(), new TimeTicks(123456)));
        }
        return response;
    }

    private PDU answer(PDU request, boolean v1) {
        PDU response = new PDU();
        response.setType(PDU.RESPONSE);
        switch (request.getType()) {
            case PDU.GET -> {
                for (VariableBinding vb : request.getVariableBindings()) {
                    Variable v = mib.get(vb.getOid());
                    response.add(new VariableBinding(vb.getOid(), v != null ? v : Null.noSuchObject));
                }
            }
            case PDU.GETNEXT -> {
                for (VariableBinding vb : request.getVariableBindings()) {
                    Map.Entry<OID, Variable> e = mib.higherEntry(vb.getOid());
                    if (e == null && v1) {                            // v1: fim da MIB é erro noSuchName
                        response.setErrorStatus(PDU.noSuchName);
                        response.setErrorIndex(1);
                        response.add(vb);
                        return response;
                    }
                    response.add(e == null ? new VariableBinding(vb.getOid(), Null.endOfMibView)
                            : new VariableBinding(e.getKey(), e.getValue()));
                }
            }
            case PDU.GETBULK -> bulk(request, response);
            default -> response.setErrorStatus(PDU.genErr);
        }
        return response;
    }

    private void bulk(PDU request, PDU response) {
        var vbs = request.getVariableBindings();
        int nonRepeaters = Math.min(request.getNonRepeaters(), vbs.size());
        OID[] cursor = new OID[vbs.size()];
        for (int i = 0; i < vbs.size(); i++) {
            cursor[i] = vbs.get(i).getOid();
        }
        for (int i = 0; i < nonRepeaters; i++) {
            Map.Entry<OID, Variable> e = mib.higherEntry(cursor[i]);
            response.add(e == null ? new VariableBinding(cursor[i], Null.endOfMibView)
                    : new VariableBinding(e.getKey(), e.getValue()));
        }
        for (int rep = 0; rep < request.getMaxRepetitions(); rep++) {
            boolean any = false;
            for (int i = nonRepeaters; i < vbs.size(); i++) {
                Map.Entry<OID, Variable> e = mib.higherEntry(cursor[i]);
                if (e == null) {
                    response.add(new VariableBinding(cursor[i], Null.endOfMibView));
                } else {
                    response.add(new VariableBinding(e.getKey(), e.getValue()));
                    cursor[i] = e.getKey();
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
    }

    @Override
    public void close() throws IOException {
        snmp.close();
    }
}
