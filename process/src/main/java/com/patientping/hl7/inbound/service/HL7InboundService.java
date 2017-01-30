package com.patientping.hl7.inbound.service;

import com.google.common.collect.ImmutableList;
import com.patientping.ejb.dao.bean.HL7MsgDao;
import com.patientping.ejb.dao.bean.InboundMsgProcessedDao;
import com.patientping.ejb.dao.bean.PatientEventDao;
import com.patientping.ejb.outbound.OutboundEventGenerator;
import com.patientping.ejb.outbound.QualityPublisherBean;
import com.patientping.ejb.pings.PingConsistencyUpdaterBean;
import com.patientping.hl7.handling.hl7msgs.HL7MsgFactory;
import com.patientping.hl7.handling.hl7msgs.HL7PreProcessorBean;
import com.patientping.jpa.entity.*;
import com.patientping.ping.hl7.exceptions.HL7MessageUniqueConstraintException;
import com.patientping.ping.hl7.exceptions.HL7PreProcessException;
import com.patientping.ping.hl7.to.HL7MsgMirthTO;
import com.patientping.ping.hl7.to.HL7MsgPPTO;
import com.patientping.ping.hl7.to.HL7PingResultTO;
import com.patientping.util.LoggingTimer;
import com.patientping.utilities.constants.EventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.util.ArrayList;
import java.util.List;

/**
 * @author cvasilyev
 */
@Service
public class HL7InboundService {

    private static final List PUBLISHABLE_EVENT_TYPES = ImmutableList.of(EventStatus.ADMIT, EventStatus.DISCHARGE);
    private static final Logger LOGGER = LoggerFactory.getLogger(HL7InboundService.class);

    @Value("${data.writeInbound:false}")
    private Boolean writeInbound;

    private final HL7PreProcessorBean mHL7Preprocessor;
    private final HL7MsgDao mHl7MsgDao;
    private final HL7MsgFactory mhl7MsgFactory;
    private final PingConsistencyUpdaterBean pingConsistencyUpdater;
    private final PatientEventDao mPatientEventDao;
    private final QualityPublisherBean qualityPublisherBean;
    private final InboundMsgProcessedDao inboundMsgProcessedDao;

    private final OutboundEventGenerator outboundEventGenerator;

    @Autowired
    public HL7InboundService(HL7PreProcessorBean mHL7Preprocessor, HL7MsgDao mHl7MsgDao, HL7MsgFactory mhl7MsgFactory,
                             PingConsistencyUpdaterBean pingConsistencyUpdater, PatientEventDao mPatientEventDao,
                             QualityPublisherBean qualityPublisherBean, InboundMsgProcessedDao inboundMsgProcessedDao,
                             OutboundEventGenerator outboundEventGenerator) {
        this.mHL7Preprocessor = mHL7Preprocessor;
        this.mHl7MsgDao = mHl7MsgDao;
        this.mhl7MsgFactory = mhl7MsgFactory;
        this.pingConsistencyUpdater = pingConsistencyUpdater;
        this.mPatientEventDao = mPatientEventDao;
        this.qualityPublisherBean = qualityPublisherBean;
        this.inboundMsgProcessedDao = inboundMsgProcessedDao;
        this.outboundEventGenerator = outboundEventGenerator;
    }


    @Transactional
    public void processHL7(HL7MsgMirthTO mirthTO) throws HL7PreProcessException, HL7MessageUniqueConstraintException {
        final Hl7Processor hl7Processor = new Hl7Processor();
        hl7Processor.processHL7(mirthTO);
    }

    private class Hl7Processor {

        private PatientEventEntity event = null;
        private RosterPatientEntity patient = null;
        private HL7MsgEntity hl7Msg = null;
        private InboundMsgProcessedEntity inboundMsg = null;

        void processHL7(HL7MsgMirthTO mirthTO) throws HL7PreProcessException, HL7MessageUniqueConstraintException {
            String controlId = mirthTO.getHl7MsgCore().getMsgCtrlId();
            LoggingTimer timer = new LoggingTimer("processHL7", controlId);

            LoggingTimer t2 = new LoggingTimer("preprocess", controlId);
            HL7MsgPPTO hl7TO = mHL7Preprocessor.preprocess(mirthTO);
            t2.end();

            t2 = new LoggingTimer("handleDuplicate", controlId);
            try {
                hl7Msg = mHl7MsgDao.findIfMessageExists(hl7TO.getHl7MsgCore().getMsgCtrlId(),
                        hl7TO.getHl7MsgCore().getSendingFacility(), hl7TO.getCreatedDate());
                if (hl7Msg != null) {
                    handleDuplicate(hl7TO);
                    return;
                }
            } finally {
                t2.end();
            }

            t2 = new LoggingTimer("inboundProcessedMsg.save", controlId);
            if (writeInbound) {
                inboundMsg = inboundMsgProcessedDao.saveHl7Msg(hl7TO);
            } else {
                hl7Msg = mHl7MsgDao.saveHl7Msg(hl7TO);
            }
            t2.end();

            //send for snf adt outbound (does not require event or ping)
            t2 = new LoggingTimer("sendSnfAdtMsg", controlId);
            //TODO does hermes need to change to read from new table? should we refactor/redo how outbound retrieves raw?
            if (writeInbound) {
                outboundEventGenerator.generateOutboundEventFromRawHL7(hl7TO.getHl7MsgCore(), inboundMsg.getId());
            } else {
                outboundEventGenerator.generateOutboundEventFromRawHL7(hl7TO.getHl7MsgCore(), hl7Msg.getId());
            }
            t2.end();

            //handle event
            t2 = new LoggingTimer("handleMsg", controlId);
            event = mhl7MsgFactory.handleMsg(hl7TO, hl7Msg);
            if (event != null) {
                hl7Msg = (event.getCancelHl7Msg() != null ? event.getCancelHl7Msg() : event.getHl7Msg());
            }
            t2.end();


            t2 = new LoggingTimer("publish to qdc", controlId);
            publishToQdc(event);
            t2.end();


            //After more refactoring, can breakout the following functions
            //event = handleEncounterUpdate(event)

            //generatePings(event)

            //sendReindex(pings)/event

            //sendOutbound(event)

            timer.end();
        }

        private void publishToQdc(PatientEventEntity event) {
            if (event == null) {
                //event is null when hl7 processes as a patient update...
                return;
            }
            if (PUBLISHABLE_EVENT_TYPES.contains(event.getTranslatedStatus())
                    && (event.getEncounter().getRelevantEvents().contains(event)
                    || event.isCanceled())) {
                LOGGER.info("sending to qdc, msg type = {}", event.getHl7Msg().getMessageType());
                qualityPublisherBean.publishEncounter(event.getEncounter());
            } else {
                LOGGER.info("NOT sending to qdc, msg type = {}", event.getHl7Msg().getMessageType());
            }
        }

        private void handleDuplicate(HL7MsgPPTO hl7MsgTO) {

            hl7Msg.setRawInput(hl7MsgTO.getHl7MsgCore().getRawInput());

            List<String> pingableGroupKeys = hl7MsgTO.getHl7MsgCore().getPingableGroupKeys();
            if (pingableGroupKeys == null) {
                return;
            }

            mHl7MsgDao.addPingableGroups(hl7Msg, pingableGroupKeys);
            event = mPatientEventDao.getEventForHl7Msg(hl7Msg);
            if (event == null) {
                return;
            }

            pingConsistencyUpdater.generatePingsAndOutboundEvents(event);
            patient = event.getEncounter().getRosterPatient();
        }

        HL7PingResultTO constructHl7PingTO() {

            HL7PingResultTO result = new HL7PingResultTO();


            if (inboundMsg != null) {
                result.setInboundMsgId(inboundMsg.getId());
            }

            if (event == null && hl7Msg == null && patient == null) {
                return result;
            }

            if (hl7Msg != null) {
                result.setHl7MsgId(hl7Msg.getId());
            }
            if (patient != null) {
                result.setEncounterPatientId(patient.getId());
                result.setEncounterGroupId(patient.getAdmittingFacility().getId());
            }

            if (event == null) {
                event = mPatientEventDao.getEventForHl7Msg(hl7Msg);
            }
            if (event != null) {
                List<Integer> pingIds = new ArrayList<>();
                List<Integer> rosterPatientIds = new ArrayList<>();
                List<Ping> pings = event.getPings();
                if (pings != null) {
                    for (Ping ping : pings) {
                        pingIds.add(ping.getId());
                        rosterPatientIds.add(ping.getRosterPatient().getId());
                    }
                }

                result.setEventId(event.getId());
                result.setEncounterGroupId(event.getEncounter().getGroup().getId());
                result.setEncounterPatientId(event.getEncounter().getRosterPatient().getId());
                result.setPingIds(pingIds);
                result.setRosterPatientIds(rosterPatientIds);
            }

            return result;
        }
    }
}
