#!/usr/bin/env python3
import asyncio
import json
from abc import ABC, abstractmethod
from datetime import datetime
import argparse
import os
import csv
import sys

#import logging
# logging.basicConfig(level=logging.DEBUG) uncomment to get logs

class Report(ABC):

    def __init__(self, msgProgressTrackerDir, protocol, protocolVersion,
                 participant1, participant2, verbose, csvFormat, jsonFormat,
                 noheader, outputFile, protocols, protocolVersions):
        self.stringOutput = ""
        self.csvOutput = []    # A dict per row and use a csv.DictWriter
        self.jsonOutput = {}   # build a dict and dump dict to JSON
        self.msgProgressTrackerDir = msgProgressTrackerDir
        self.protocol = protocol
        self.protocolVersion = protocolVersion
        self.protocols = protocols
        self.protocolVersions = protocolVersions
        self.participant1 = participant1
        with open(f'{self.msgProgressTrackerDir}/{self.participant1}/msg-progress-tracking-result.json', 'r') as f:
            self.participant1_dict = json.load(f)
        self.participant2 = participant2
        with open(f'{self.msgProgressTrackerDir}/{self.participant2}/msg-progress-tracking-result.json', 'r') as f:
            self.participant2_dict = json.load(f)
        self.verbose = verbose
        self.csvFormat = csvFormat
        self.jsonFormat = jsonFormat
        self.noheader = noheader
        self.outputFile = outputFile
        super().__init__()

    def generateReport(self):
        if self.jsonFormat:
            if self.outputFile:
                with open(self.outputFile, 'w') as f:
                    json.dump(self.jsonOutput, f)
            else:
                print(json.dumps(self.jsonOutput), end='')
        elif self.csvFormat:
            fieldnames = ["protocol", "protocolVersion", "participant", "receivedAt", "finishedAt", "pinstId",
                          "relTrackingId", "timeTakenMillis", "inMsgName", "inMsgType", "outMsgName", "outMsgType"]
            if self.outputFile:
                with open(self.outputFile, 'w', newline='') as f:
                    writer = csv.DictWriter(f, fieldnames=fieldnames)
                    if not self.noheader:
                        writer.writeheader()
                    writer.writerows(self.csvOutput)
            else:
                writer = csv.DictWriter(sys.stdout, fieldnames=fieldnames)
                if not self.noheader:
                    writer.writeheader()
                writer.writerows(self.csvOutput)
        else:
            if self.outputFile:
                with open(self.outputFile, 'w') as f:
                    f.write(self.stringOutput)
            else:
                print(self.stringOutput, end='')

    def millisDiff(self, start, end) -> int:
        try:
            startDateTime = datetime.strptime(start, '%Y-%m-%dT%H:%M:%S.%fZ')
        except ValueError as e:
            # Until we know if microsecond precision missing is a bug or not
            # Assume a start time w/o microsecond precision is NOT a bug
            startDateTime = datetime.strptime(start, '%Y-%m-%dT%H:%M:%SZ')

        try:
            endDateTime = datetime.strptime(end, '%Y-%m-%dT%H:%M:%S.%fZ')
        except ValueError as e:
            # Until we know if microsecond precision missing is a bug or not
            # Assume a end time w/o microsecond precision is NOT a bug
            endDateTime = datetime.strptime(end, '%Y-%m-%dT%H:%M:%SZ')

        return (endDateTime.timestamp() * 1000) - (startDateTime.timestamp() * 1000)

    def renderJson(self, protocol, protocolVersion, participant, receivedAt, finishedAt, pinstId, relTrackingId,
                   timeTakenMillis, inMsgName, inMsgType, outMsgName, outMsgType, isPinst=False):
        p = self.jsonOutput.get(protocol, {})
        pv = p.get(protocolVersion, {})
        par = pv.get(participant, {})
        pd = par.get("pinst-detail", {})
        if isPinst:
            rt = pd.get(inMsgName, {})
        else:
            rt = par.get(inMsgName, {})
        ttms = rt.get('timeTakenMillis')
        slowerTime = ((timeTakenMillis or 0) > (ttms or 0))
        if slowerTime:
            if (ttms or 0) > 0:
                fttm = rt.get('fasterTimeTakenMillis', [])
                fttm.append(ttms)
                rt['fasterTimeTakenMillis'] = fttm
            rt['receivedAt'] = receivedAt or None
            rt['finishedAt'] = finishedAt or None
            rt['pinstId'] = pinstId or None
            rt['relTrackingId'] = relTrackingId or None
            rt['timeTakenMillis'] = timeTakenMillis or None
            rt['inMsgType'] = inMsgType or None
            rt['outMsgName'] = outMsgName or None
            rt['outMsgType'] = outMsgType or None
        elif (timeTakenMillis or 0) > 0:
            fttm = rt.get('fasterTimeTakenMillis', [])
            fttm.append(timeTakenMillis)
            rt['fasterTimeTakenMillis'] = fttm
        else:
            rt['receivedAt'] = receivedAt or None
            rt['finishedAt'] = finishedAt or None
            rt['pinstId'] = pinstId or None
            rt['relTrackingId'] = relTrackingId or None
            rt['timeTakenMillis'] = timeTakenMillis or None
            rt['inMsgType'] = inMsgType or None
            rt['outMsgName'] = outMsgName or None
            rt['outMsgType'] = outMsgType or None
        if isPinst:
            pd[inMsgName] = rt
            par["pinst-detail"] = pd
        else:
            par[inMsgName] = rt
        pv[participant] = par
        p[protocolVersion] = pv
        self.jsonOutput[protocol] = p

    def renderCSV(self, protocol, protocolVersion, participant, receivedAt, finishedAt, pinstId, relTrackingId,
                  timeTakenMillis, inMsgName, inMsgType, outMsgName, outMsgType, isPinst):
        self.csvOutput.append(
            {
                "protocol": protocol,
                "protocolVersion": protocolVersion,
                "participant": participant,
                "receivedAt": receivedAt,
                "finishedAt": finishedAt,
                "pinstId": pinstId,
                "relTrackingId": relTrackingId,
                "timeTakenMillis": timeTakenMillis,
                "inMsgName": inMsgName,
                "inMsgType": inMsgType,
                "outMsgName": outMsgName,
                "outMsgType": outMsgType
            }
        )
        #self.stringOutput += f'\"{protocol or ""}\",\"{protocolVersion or ""}\",\"{participant or ""}\",' \
        #                     f'\"{receivedAt or ""}\",\"{finishedAt or ""}\",\"{pinstId or ""}\",' \
        #                     f'\"{relTrackingId or ""}\",{timeTakenMillis},\"{inMsgName or ""}\",' \
        #                     f'\"{inMsgType}\",\"{outMsgName}\",\"{outMsgType}\"\n'

    def render(self, protocol, protocolVersion, participant, receivedAt,
               finishedAt, pinstId, relTrackingId, timeTakenMillis, inMsgName,
               inMsgType, outMsgName, outMsgType, isPinst=False):
        if self.jsonFormat:
            self.renderJson(protocol, protocolVersion, participant, receivedAt,
                            finishedAt, pinstId, relTrackingId, timeTakenMillis,
                            inMsgName, inMsgType, outMsgName, outMsgType, isPinst)
        elif self.csvFormat:
            self.renderCSV(protocol, protocolVersion, participant, receivedAt,
                           finishedAt, pinstId, relTrackingId, timeTakenMillis,
                           inMsgName, inMsgType, outMsgName, outMsgType, isPinst)
        else:
            self.stringOutput += f'{"":4} {participant or "":11} {receivedAt or "":25} {finishedAt or "":25} ' \
                                 f'{pinstId or "":32} {relTrackingId or "":15} {timeTakenMillis or "":8} ' \
                                 f'{inMsgName or "":25} {inMsgType or "":14} ' \
                                 f'{outMsgName or "":25} {outMsgType or "":14}\n'

    def renderProtocolInstanceBenchmarks(self, participant, pinstId, relTrackingId):
        previousTimestamp = None
        with open(f'{self.msgProgressTrackerDir}/{participant}/{pinstId}.json', 'r') as f:
            pinstDict = json.load(f)
            requests = reversed(pinstDict.get("requests"))
            for request in requests:
                events = request.get("events")
                summary = request.get("summary")
                inMsgParam = summary.get("inMsgParam", {})
                inMsgName = inMsgParam.get("msgName")
                inMsgType = inMsgParam.get("msgType")
                outMsgParam = summary.get("outMsgParam", {})
                outMsgName = outMsgParam.get("msgName", "")
                outMsgType = outMsgParam.get("msgType", "")
                receivedAt = request.get("receivedAt", "")
                finishedAt = request.get("finishedAt", "")

                if receivedAt and finishedAt:
                    timeTakenMillis = self.millisDiff(receivedAt, finishedAt)
                    previousTimestamp = finishedAt
                else:
                    if receivedAt and previousTimestamp:
                        timeTakenMillis = self.millisDiff(previousTimestamp, receivedAt)
                        previousTimestamp = receivedAt
                    else:
                        timeTakenMillis = ""
                p = ""
                if self.jsonFormat:
                    p = participant
                self.render(self.protocol, self.protocolVersion, p, receivedAt, finishedAt, pinstId, relTrackingId,
                            timeTakenMillis, inMsgName, inMsgType, outMsgName, outMsgType, isPinst=True)

    def renderRequestBenchmark(self, requests) -> str:
        renderedPinstIds = []
        targetProtocol = self.protocol
        targetProtocolVersion = self.protocolVersion
        trackedRelationships = []
        if not self.csvFormat and not self.jsonFormat and not self.noheader:
            self.stringOutput += f'{"":4} {"participant":11} {"receivedAt":25} {"finishedAt":25} {"pinstId":32} ' \
                                 f'{"relTrackingId":15} {"Millis":8} {"inMsgName":25} {"inMsgType":14} ' \
                                 f'{"outMsgName":25} {"outMsgType":14}\n'
        for request in requests:
            participant = request.get("participant", "")
            summary = request.get("summary")
            trackingParam = summary.get("trackingParam")
            relTrackingId = trackingParam.get("relTrackingId", "")

            inMsgParam = summary.get("inMsgParam", {})
            inMsgName = inMsgParam.get("msgName", "")
            inMsgType = inMsgParam.get("msgType", "")

            outMsgParam = summary.get("outMsgParam", {})
            outMsgName = outMsgParam.get("msgName", "")
            outMsgType = outMsgParam.get("msgType", "")

            receivedAt = request.get("receivedAt", "")
            finishedAt = request.get("finishedAt", "")
            if receivedAt and finishedAt:
                timeTakenMillis = self.millisDiff(receivedAt, finishedAt)
            else:
                timeTakenMillis = ""

            protoDetail = summary.get("protoParam")
            if protoDetail:
                familyName = protoDetail.get("familyName")
                familyVersion = protoDetail.get("familyVersion")
                if familyName == targetProtocol and familyVersion == targetProtocolVersion:
                    pinstId = protoDetail.get("pinstId")
                    self.render(self.protocol, self.protocolVersion, participant, receivedAt, finishedAt, pinstId,
                                relTrackingId, timeTakenMillis, inMsgName, inMsgType, outMsgName, outMsgType)
                    if relTrackingId and relTrackingId not in trackedRelationships:
                        trackedRelationships.append(relTrackingId)
                    if self.verbose and pinstId and pinstId not in renderedPinstIds:
                        self.renderProtocolInstanceBenchmarks(participant, pinstId, relTrackingId)
                        renderedPinstIds.append(pinstId)
            elif relTrackingId in trackedRelationships:
                self.render(self.protocol, self.protocolVersion, participant, receivedAt, finishedAt, "", relTrackingId,
                            timeTakenMillis, inMsgName, inMsgType, outMsgName, outMsgType)

    def setParticipant(self, participant, requests):
        for request in requests:
            request["participant"] = participant
        return requests

    def mergeByReceivedAt(self, p1Requests, p2Requests):
        return sorted(
            self.setParticipant(self.participant1, p1Requests) + self.setParticipant(self.participant2, p2Requests),
            key=lambda i: i['receivedAt']
        )

    def renderBenchmarks(self):
        sortedRequests = self.mergeByReceivedAt(self.participant1_dict.get("requests"),
                                                self.participant2_dict.get("requests"))
        self.renderRequestBenchmark(sortedRequests)

    @abstractmethod
    async def run(self):
        pass


class ProtocolReport(Report):
    async def run(self):
        if not self.csvFormat and not self.jsonFormat and not self.noheader:
            self.stringOutput += f"Producing protocol report for protocol: {self.protocol} at version: {self.protocolVersion}\n"

        if self.protocol == 'all' and self.protocolVersion == 'all':
            for v in self.protocolVersions:
                self.protocolVersion = v
                for p in self.protocols:
                    self.protocol = p
                    if not self.csvFormat and not self.jsonFormat and not self.noheader:
                        self.stringOutput += f"\n{self.protocol} {self.protocolVersion}\n"
                    self.renderBenchmarks()
        elif self.protocol == 'all':
            for p in self.protocols:
                self.protocol = p
                if not self.csvFormat and not self.jsonFormat and not self.noheader:
                    self.stringOutput += f"\n{self.protocol} {self.protocolVersion}\n"
                self.renderBenchmarks()
        elif self.protocolVersion == 'all':
            for v in self.protocolVersions:
                self.protocolVersion = v
                if not self.csvFormat and not self.jsonFormat and not self.noheader:
                    self.stringOutput += f"\n{self.protocol} {self.protocolVersion}\n"
                self.renderBenchmarks()
        else:
            if not self.csvFormat and not self.jsonFormat and not self.noheader:
                self.stringOutput += f"\n{self.protocol} {self.protocolVersion}\n"
            self.renderBenchmarks()

        self.generateReport()


async def main():
    defaultDir = f"{os.path.dirname(os.path.realpath(__file__))}" \
                 f"/../../target/scalatest-runs/last-suite/MsgProgressTracker"
    protocols = [
        "agent-provisioning",
        "issuer-setup",
        "update-configs",
        "write-schema",
        "write-cred-def",
        "relationship",
        "connections",
        "connecting",
        "issue-credential",
        "present-proof",
        "committedanswer"
    ]
    protocolVersions = ["0.5", "0.6", "1.0"]
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=False)
    group.add_argument("--json", help="Produce JSON output. The JSON output is "
                                       "formatted fundamentally different from csv "
                                       "and default (tabular) format. It is designed "
                                       "to keep track of the slowest benchmark for "
                                       "each inMsgName. Only the timeTakenInMillis "
                                       "is preserved from messages that were faster.", action="store_true")
    group.add_argument("--csv", help="Produce CSV output", action="store_true")
    parser.add_argument("--verbose", help="Increase output verbosity to include protocol instance benchmarks",
                        action="store_true")
    parser.add_argument("--noheader", help="Exclude header output", action="store_true")
    parser.add_argument("-d", "--dir", type=str, default=defaultDir,
                        help=f"Directory containing MsgProgressTracker JSON files. Default: {defaultDir}")
    parser.add_argument("-p", "--protocol", type=str, default="all", choices=protocols,
                        help="Protocol from which to extract timing metrics. Default: all")
    parser.add_argument("-v", "--protocolVersion", type=str, default="all", choices=protocolVersions,
                        help="Version of the protocol. Default: all")
    parser.add_argument("-s", "--participant1", type=str, default="VERITY1", choices=["VERITY1", "CAS1"],
                        help="This agent started the <protocol>. Default: VERITY1")
    # TODO: make this option repeatable to include other participants in the event that issuer isn't also the verifier
    parser.add_argument("-r", "--participant2", type=str, default="CAS1", choices=["VERITY1", "CAS1"],
                        help="Which other agent participates in the protocol? Default: CAS1")
    parser.add_argument("-o", "--outputFile", type=str, help="Output file")
    args = parser.parse_args()

    report = ProtocolReport(args.dir, args.protocol, args.protocolVersion,
                            args.participant1, args.participant2, args.verbose,
                            args.csv, args.json, args.noheader, args.outputFile,
                            protocols, protocolVersions)
    await report.run()


if __name__ == '__main__':
    loop = asyncio.get_event_loop()
    loop.run_until_complete(main())
