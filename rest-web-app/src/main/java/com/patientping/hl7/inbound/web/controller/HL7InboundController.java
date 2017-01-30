package com.patientping.hl7.inbound.web.controller;

import com.patientping.hl7.inbound.service.HL7InboundService;
import com.patientping.ping.hl7.to.HL7MsgMirthTO;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provides the request handler to for the REST calls to receive and process incoming HL7 messages.
 */
@RestController
public class HL7InboundController {

    private final HL7InboundService inboundService;

    private static final Logger LOGGER = LoggerFactory.getLogger(HL7InboundController.class);

    @Autowired
    public HL7InboundController(HL7InboundService inboundServiceImpl) {
        this.inboundService = inboundServiceImpl;
    }

    @RequestMapping(value = "hl7", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE)
    public String acceptHL7Message(@RequestBody HL7MsgMirthTO hl7MsgMirthTO) throws Exception {
        try {
            MDC.put("message_control_id", extractMsgCtrlId(hl7MsgMirthTO));

            inboundService.processHL7(hl7MsgMirthTO);

            LOGGER.info("Finished from KafkaConsumer");
            return "OK";
        } catch (Exception e) {
            LOGGER.error("Error processing HL7!", e);
            throw e;
        } finally {
            MDC.remove("message_control_id");
        }
    }

    private static String extractMsgCtrlId(HL7MsgMirthTO hl7MsgMirthTO) {
        if (hl7MsgMirthTO == null) {
            return "-";
        }
        if (hl7MsgMirthTO.getHl7MsgCore() == null) {
            return "-";
        }
        if (StringUtils.isBlank(hl7MsgMirthTO.getHl7MsgCore().getMsgCtrlId())) {
            return "-";
        }
        return hl7MsgMirthTO.getHl7MsgCore().getMsgCtrlId();
    }
}
