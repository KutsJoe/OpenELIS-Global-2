/**
 * The contents of this file are subject to the Mozilla Public License
 * Version 1.1 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. See the
 * License for the specific language governing rights and limitations under
 * the License.
 *
 * The Original Code is OpenELIS code.
 *
 * Copyright (C) CIRG, University of Washington, Seattle WA.  All Rights Reserved.
 *
 */
package org.openelisglobal.dataexchange.resultreporting;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletResponse;

import org.hl7.fhir.r4.model.Annotation;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.Task.TaskOutputComponent;
import org.hl7.fhir.r4.model.Task.TaskStatus;
import org.openelisglobal.common.exception.LIMSRuntimeException;
import org.openelisglobal.common.log.LogEvent;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.ExternalOrderStatus;
import org.openelisglobal.common.util.DateUtil;
import org.openelisglobal.dataexchange.aggregatereporting.valueholder.ReportExternalExport;
import org.openelisglobal.dataexchange.aggregatereporting.valueholder.ReportQueueType;
import org.openelisglobal.dataexchange.common.ITransmissionResponseHandler;
import org.openelisglobal.dataexchange.common.ReportTransmission;
import org.openelisglobal.dataexchange.fhir.service.FhirApiWorkflowService;
import org.openelisglobal.dataexchange.fhir.service.FhirTransformService;
import org.openelisglobal.dataexchange.order.valueholder.ElectronicOrder;
import org.openelisglobal.dataexchange.orderresult.OrderResponseWorker.Event;
import org.openelisglobal.dataexchange.orderresult.valueholder.HL7MessageOut;
import org.openelisglobal.dataexchange.resultreporting.beans.ResultReportXmit;
import org.openelisglobal.dataexchange.resultreporting.beans.TestResultsXmit;
import org.openelisglobal.dataexchange.service.aggregatereporting.ReportExternalExportService;
import org.openelisglobal.dataexchange.service.aggregatereporting.ReportQueueTypeService;
import org.openelisglobal.dataexchange.service.order.ElectronicOrderService;
import org.openelisglobal.dataexchange.service.orderresult.HL7MessageOutService;
import org.openelisglobal.referencetables.service.ReferenceTablesService;
import org.openelisglobal.referencetables.valueholder.ReferenceTables;
import org.openelisglobal.reports.service.DocumentTrackService;
import org.openelisglobal.reports.service.DocumentTypeService;
import org.openelisglobal.reports.valueholder.DocumentTrack;
import org.openelisglobal.reports.valueholder.DocumentType;
import org.openelisglobal.result.valueholder.Result;
import org.openelisglobal.spring.util.SpringContext;
import org.springframework.beans.factory.annotation.Value;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.model.api.Include;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.TokenClientParam;

public class ResultReportingTransfer {

    @Value("${org.openelisglobal.fhirstore.uri}")
    private String localFhirStorePath;

    private FhirContext fhirContext = SpringContext.getBean(FhirContext.class);

    private Task task = null;
    private Task eTask = null;
    private ServiceRequest serviceRequest = null;
    private Patient patient = null;
    protected ElectronicOrderService electronicOrderService = SpringContext.getBean(ElectronicOrderService.class);
    protected FhirApiWorkflowService fhirApiWorkFlowService = SpringContext.getBean(FhirApiWorkflowService.class);
    protected FhirTransformService fhirTransformService = SpringContext.getBean(FhirTransformService.class);

    private static DocumentType DOCUMENT_TYPE;
    private static String QUEUE_TYPE_ID;
    private static String RESULT_REFERRANCE_TABLE_ID;

    static {
        DOCUMENT_TYPE = SpringContext.getBean(DocumentTypeService.class).getDocumentTypeByName("resultExport");
        ReferenceTables referenceTable = SpringContext.getBean(ReferenceTablesService.class)
                .getReferenceTableByName("RESULT");
        if (referenceTable != null) {
            RESULT_REFERRANCE_TABLE_ID = referenceTable.getId();
        }
        ReportQueueType queueType = SpringContext.getBean(ReportQueueTypeService.class)
                .getReportQueueTypeByName("Results");
        if (queueType != null) {
            QUEUE_TYPE_ID = queueType.getId();
        }
    }

    public void sendResults(ResultReportXmit resultReport, List<Result> reportingResult, String url) {
        IGenericClient localFhirClient = fhirContext
                .newRestfulGenericClient(fhirApiWorkFlowService.getLocalFhirStorePath());
       
        for (TestResultsXmit result : resultReport.getTestResults()) {
            if (result.getReferringOrderNumber() == null) { // walk-in create FHIR
                String fhir_json = fhirTransformService.CreateFhirFromOESample(result);
                System.out.println("" + fhir_json);
                break;
            }
            if (!result.getReferringOrderNumber().isEmpty()) { // eOrder create FHIR

                String orderNumber = result.getReferringOrderNumber();
                List<ElectronicOrder> eOrders = electronicOrderService.getElectronicOrdersByExternalId(orderNumber);
                ElectronicOrder eOrder = eOrders.get(eOrders.size() - 1);
                ExternalOrderStatus eOrderStatus = SpringContext.getBean(IStatusService.class)
                        .getExternalOrderStatusForID(eOrder.getStatusId());
                
//              fhirTransformService.CreateFhirFromOESample(eOrder);

                eTask = fhirContext.newJsonParser().parseResource(Task.class, eOrder.getData());
                
                // using UUID from getData which is idPart in original etask
                Bundle tsrBundle = (Bundle) localFhirClient.search().forResource(Task.class)
                        .where(new TokenClientParam("identifier").exactly().code(eTask.getIdElement().getIdPart()))
                        .include(new Include("Task:based-on"))
                        .prettyPrint()
                        .execute();
             
                task = null; 
                List<ServiceRequest> serviceRequestList = new ArrayList<ServiceRequest>();
                for (BundleEntryComponent bundleComponent : tsrBundle.getEntry()) {
                    if (bundleComponent.hasResource()
                            && ResourceType.Task.equals(bundleComponent.getResource().getResourceType())) {
                        task = (Task) bundleComponent.getResource();
                    }
                    
                    if (bundleComponent.hasResource()
                            && ResourceType.ServiceRequest.equals(bundleComponent.getResource().getResourceType())) {
                            
                        ServiceRequest serviceRequest = (ServiceRequest) bundleComponent.getResource();
                        for(Identifier identifier : serviceRequest.getIdentifier()) {
                            if(identifier.getValue().equals(orderNumber))
                                serviceRequestList.add((ServiceRequest) bundleComponent.getResource());
                        }
                    }
                }
                
                for (ServiceRequest serviceRequest : serviceRequestList) {
                 // task has to be refreshed after each loop 
                 // using UUID from getData which is idPart in original etask
                    Bundle tBundle = (Bundle) localFhirClient.search().forResource(Task.class)
                            .where(new TokenClientParam("identifier").exactly().code(eTask.getIdElement().getIdPart()))
                            .prettyPrint()
                            .execute();
                 
                    task = null; 
                    for (BundleEntryComponent bundleComponent : tBundle.getEntry()) {
                        if (bundleComponent.hasResource()
                                && ResourceType.Task.equals(bundleComponent.getResource().getResourceType())) {
                            task = (Task) bundleComponent.getResource();
                        }
                    }
                    
                    Bundle pBundle = (Bundle) localFhirClient.search().forResource(Patient.class)
                            .where(new TokenClientParam("_id").exactly()
                                    .code(serviceRequest.getSubject().getReferenceElement().getIdPart()))
                            .prettyPrint().execute();

                    patient = null;
                    for (BundleEntryComponent bundleComponent : pBundle.getEntry()) {
                        if (bundleComponent.hasResource()
                                && ResourceType.Patient.equals(bundleComponent.getResource().getResourceType())) {
                            patient = (Patient) bundleComponent.getResource();
                        }
                    }

                    try {
                        Observation observation = new Observation();
                        observation.setIdentifier(serviceRequest.getIdentifier());
                        observation.setBasedOn(serviceRequest.getBasedOn());
                        observation.setStatus(Observation.ObservationStatus.FINAL);
                        observation.setCode(serviceRequest.getCode());
                        Reference subjectRef = new Reference();
                        subjectRef.setReference(patient.getResourceType() + "/" + patient.getIdElement().getIdPart());
                        observation.setSubject(subjectRef);
                        // TODO: numeric, check for other result types
                        Quantity quantity = new Quantity();
                        quantity.setValue(new java.math.BigDecimal(result.getResults().get(0).getResult().getText()));
                        quantity.setUnit(result.getUnits());
                        observation.setValue(quantity);

                        Annotation oNote = new Annotation();
                        oNote.setText(result.getTestNotes());
                        observation.addNote(oNote);

                        MethodOutcome oOutcome = localFhirClient.create().resource(observation).execute();

                        DiagnosticReport diagnosticReport = new DiagnosticReport();
                        diagnosticReport.setId(result.getTest().getCode());
                        diagnosticReport.setIdentifier(serviceRequest.getIdentifier());
                        diagnosticReport.setBasedOn(serviceRequest.getBasedOn());
                        diagnosticReport.setStatus(DiagnosticReport.DiagnosticReportStatus.FINAL);
                        diagnosticReport.setCode(serviceRequest.getCode());
                        diagnosticReport.setSubject(subjectRef);

                        Reference observationReference = new Reference();
                        observationReference.setType(oOutcome.getId().getResourceType());
                        observationReference
                                .setReference(oOutcome.getId().getResourceType() + "/" + oOutcome.getId().getIdPart());
                        diagnosticReport.addResult(observationReference);
                        MethodOutcome drOutcome = localFhirClient.create().resource(diagnosticReport).execute();

                        Reference diagnosticReportReference = new Reference();
                        diagnosticReportReference.setType(drOutcome.getId().getResourceType());
                        diagnosticReportReference.setReference(
                                drOutcome.getId().getResourceType() + "/" + drOutcome.getId().getIdPart());

                        TaskOutputComponent theOutputComponent = new TaskOutputComponent();
                        theOutputComponent.setValue(diagnosticReportReference);
                        task.addOutput(theOutputComponent);
                        task.setStatus(TaskStatus.COMPLETED);

                        localFhirClient.update().resource(task).execute();

                    } catch (Exception e) {
                        System.out.println("Result update exception: " + e.getStackTrace());
                    }
                }
                break;
            }
            if (result.getReferringOrderNumber().isEmpty()) { // HL7
                ITransmissionResponseHandler responseHandler = new ResultFailHandler(reportingResult);
                new ReportTransmission().sendHL7Report(resultReport, url, responseHandler);
                break;
            }
        }
    }

    class ResultFailHandler implements ITransmissionResponseHandler {

        private List<Result> reportingResults;

        public ResultFailHandler(List<Result> reportingResults) {
            this.reportingResults = reportingResults;
        }

        @Override
        public void handleResponse(int httpReturnStatus, List<String> errors, String msg) {
            if (httpReturnStatus == HttpServletResponse.SC_OK) {
                markFinalResultsAsSent();
                persistMessage(msg, true);
            } else {
                bufferResults(msg);
                persistMessage(msg, false);
            }
        }

        private void persistMessage(String msg, boolean success) {
            HL7MessageOutService messageOutService = SpringContext.getBean(HL7MessageOutService.class);
            HL7MessageOut messageOut = new HL7MessageOut();
            messageOut.setData(msg);
            if (success) {
                messageOut.setStatus(HL7MessageOut.SUCCESS);
            } else {
                messageOut.setStatus(HL7MessageOut.FAIL);
            }
            messageOutService.insert(messageOut);
        }

        private void bufferResults(String msg) {
            ReportExternalExport report = new ReportExternalExport();
            report.setData(msg);
            report.setSysUserId("1");
            report.setEventDate(DateUtil.getNowAsTimestamp());
            report.setCollectionDate(DateUtil.getNowAsTimestamp());
            report.setTypeId(QUEUE_TYPE_ID);
            report.setBookkeepingData(getResultIdListString() == null ? "" : getResultIdListString());
            report.setSend(true);

            try {
                SpringContext.getBean(ReportExternalExportService.class).insert(report);
            } catch (LIMSRuntimeException e) {
                LogEvent.logErrorStack(e);
            }
        }

        private String getResultIdListString() {
            String comma = "";

            StringBuilder builder = new StringBuilder();

            for (Result result : reportingResults) {
                builder.append(comma); // empty first time through
                builder.append(result.getId());

                comma = ",";
            }

            return builder.toString();
        }

        private void markFinalResultsAsSent() {
            Timestamp now = DateUtil.getNowAsTimestamp();

            List<DocumentTrack> documents = new ArrayList<>();

            for (Result result : reportingResults) {
                if (result.getResultEvent() == Event.FINAL_RESULT || result.getResultEvent() == Event.CORRECTION) {
                    DocumentTrack document = new DocumentTrack();
                    document.setDocumentTypeId(DOCUMENT_TYPE.getId());
                    document.setRecordId(result.getId());
                    document.setReportTime(now);
                    document.setTableId(RESULT_REFERRANCE_TABLE_ID);
                    document.setSysUserId("1");
                    documents.add(document);
                }
            }

            DocumentTrackService trackService = SpringContext.getBean(DocumentTrackService.class);

            try {
                trackService.insertAll(documents);
//				for (DocumentTrack document : documents) {
//					trackService.insert(document);
//				}
            } catch (LIMSRuntimeException e) {
                LogEvent.logErrorStack(e);
            }
        }
    }
}
