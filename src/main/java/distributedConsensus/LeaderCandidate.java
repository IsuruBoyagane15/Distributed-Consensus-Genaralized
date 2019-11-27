package distributedConsensus;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.graalvm.polyglot.Value;
import org.apache.log4j.Logger;

/**
 * Java instance participating to leader election
 * Can become a leader or a follower
 */
public class LeaderCandidate extends ConsensusApplication implements Runnable{

    enum roundStatuses {
        ONGOING,
        NEW,
        FINISHED
    }
    private static final Logger LOGGER = Logger.getLogger(LeaderCandidate.class);
    private roundStatuses joiningState;
    private boolean timeoutCounted, terminate;
    private int roundNumber;
    private HeartbeatListener heartbeatListener;
    private String electedLeader;
    private final String initialJsCode;

    /**
     * Constructor
     *
     * @param nodeId unique id to identify the LeaderCandidate
     * @param runtimeJsCode String containing Javascript records
     * @param evaluationJsCode Javascript logic to evaluate and elect a leader
     * @param kafkaServerAddress URL of Kafka server
     * @param kafkaTopic Kafka topic to subscribe to participate to leader election
     */
    public LeaderCandidate(String nodeId, String runtimeJsCode, String evaluationJsCode, String
            kafkaServerAddress, String kafkaTopic) {
        super(nodeId, runtimeJsCode, evaluationJsCode, kafkaServerAddress, kafkaTopic);
        this.initialJsCode = runtimeJsCode;
        this.heartbeatListener = null;
        this.electedLeader = null;
        this.timeoutCounted = false; //FIRST LEADER CANDIDATE TO JOIN A ROUND WILL COUNT A
        // TIMEOUT AND CLOSE THE VOTING BY WRITING TO KAFKA
        this.joiningState = null; //STATE OF THE ROUND WHEN NODE PARTICIPATED;
        this.terminate = false;
    }

    /**
     * Get terminate
     *
     * @return whether to terminate or not
     */
    public boolean isTerminate() {
        return terminate;
    }

    /**
     * Set terminate
     *
     * @param terminate boolean value to be set to terminate
     */
    public void setTerminate(boolean terminate) {
        this.terminate = terminate;
    }

    /**
     * Set electedLeader
     *
     * @param electedLeader Id to be set to electedLeader
     */
    public void setElectedLeader(String electedLeader) {
        this.electedLeader = electedLeader;
    }

    /**
     * Decide the state of Kafka log, round number of the round to participate
     * Write record into Kafka based on the round state and number
     *
     * @param lastRoundNumber highest round number in the log before LeaderCandidates CHECK record
     * @param lastRoundJsCodes code segment of round of lastRoundNumber
     */
    public void participate(int lastRoundNumber, String lastRoundJsCodes) {
        int nodeRank = (int)(1 + Math.random()*100);
        this.roundNumber = lastRoundNumber;
        this.setRuntimeJsCode(initialJsCode);

        if (lastRoundJsCodes.equals("")){
            //EMPTY KAFKA LOG
            this.joiningState = roundStatuses.NEW;
            this.distributedConsensus.writeACommand(this.roundNumber+ ",if(!result.timeout){" +
                    "nodeRanks.push({client:\""+ getNodeId() + "\",rank:" + nodeRank +"});}");
            LOGGER.info("Participated to NEW round :" + roundNumber + "; rank is " + nodeRank);
        }
        else{
            //NON-EMPTY KAFKA LOG
            Value latestRoundResult = this.distributedConsensus.evaluateJsCode(lastRoundJsCodes);
            boolean isRoundFinished = this.checkConsensus(latestRoundResult);
            if (isRoundFinished){
                //NON-EMPTY KAFKA LOG WITH FINISHED ROUND
                this.joiningState = roundStatuses.FINISHED;
                LOGGER.info("Waiting for HBs of FINISHED round " + roundNumber + "; Or will join to" +
                        " round " + (roundNumber + 1));
                startHeartbeatListener();
            }
            else{
                //NON-EMPTY KAFKA LOG WITH ONGOING ROUND
                this.joiningState = roundStatuses.ONGOING;
                setRuntimeJsCode(initialJsCode + lastRoundJsCodes);
                this.distributedConsensus.writeACommand(this.roundNumber + ",if(!result.timeout)" +
                        "{nodeRanks.push({client:\""+ getNodeId() + "\",rank:" + nodeRank +"});}");
                LOGGER.info("Participated to ONGOING round :" + roundNumber + "JsCode : " +
                        lastRoundJsCodes + "; rank is " + nodeRank);
            }
        }
    }

    //SHOULD NOT BE CALLED WHEN THERE IS AN IDENTIFIED LEADER

    /**
     * Action on receiving evaluated result of a Kafka record
     *
     * @param result  result of Javascript evaluation
     * @return whether consensus achieved or not
     */
    public boolean onReceiving(Value result) {
        if(electedLeader == null){
            if (result.getMember("firstCandidate").toString().equals(getNodeId()) && !timeoutCounted){
                long timeout = 500;
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                this.timeoutCounted = true;
                this.distributedConsensus.writeACommand(this.roundNumber + ",result.timeout = true;");
                LOGGER.info("Waited " + timeout + "ms and wrote \"result.timeout = true;\" to close " +
                        "the vote counting");
                return false;
            }
            else{
                return checkConsensus(result);
            }
        }
        else{
            LOGGER.warn("Record of same round " + roundNumber + "after leader is elected");
            return false;
        }
    }

    /**
     * Action on electing a leader
     *
     * @param value result of Javascript evaluation containing leaders id
     */
    @Override
    public void onConsensus(Value value) {
        this.electedLeader = value.getMember("value").toString();
        LOGGER.info(getNodeId() + " :: " + this.electedLeader + " is elected as the leader");
        if (value.getMember("value").toString().equals(getNodeId())) {
            this.startHeartbeatSender();
        }
        else{
            this.startHeartbeatListener();
        }
    }

    /**
     * Handle a heartbeat
     */
    public void handleHeartbeat() {
        this.heartbeatListener.interrupt();
    }

    /**
     * Extract whether a leader is elected or not from Javascript result
     *
     * @param result Javascript evaluation result
     * @return whether a leader is elected or not
     */
    @Override
    public boolean checkConsensus(Value result) {
        return result.getMember("consensus").asBoolean();
    }

    /**
     * Run method of LeaderCandidate thread
     * Generate and write unique hash to Kafka
     * Start consuming Kafka records
     * Extract highest rounds details written before the CHECK record
     * Call participate when CHECK record is found
     * Evaluate Javascript until a leader is elected
     * Call Heartbeat listening/sending based on the result
     */
    public void run(){
        final String rawString = this.generateUniqueKey();
        final String unique_round_key = DigestUtils.sha256Hex(rawString);
        final String checkRecord = "CHECK,"+ unique_round_key;

        this.distributedConsensus.writeACommand(checkRecord);
        LOGGER.info("Started; Id : " + getNodeId() + "; " + "check message : " + checkRecord);

        boolean correctRoundIdentified = false;

        String latestRoundsJsCode = "";
        int latestRoundNumber = 0;

        try {
            while (!this.terminate) {
                ConsumerRecords<String, String> records = this.distributedConsensus.getMessages();
                for (ConsumerRecord<String, String> record : records) {
                    if (!correctRoundIdentified){
                        //IDENTIFYING THE ROUND
                        if (record.value().equals(checkRecord)) {
                            //TAKE DECISION ON ROUND STATUS BASED ON COLLECTED LAST ROUND CODES AND
                            // PARTICIPATE
                            LOGGER.info("Found check record : " + checkRecord);
                            this.participate(latestRoundNumber,latestRoundsJsCode);
                            correctRoundIdentified = true;

                        }
                        else if (!record.value().startsWith("CHECK,")){ //A NODE ONLY CONSIDER IT'S
                            // CHECK RECORD
                            //COLLECT MOST RECENT ROUND'S CODE
                            String[] recordContent = record.value().split(",", 2);
                            int recordRoundNumber = Integer.parseInt(recordContent[0]);
                            String recordMessage = recordContent[1]; //ALIVE or clean JS

                            if (!recordMessage.startsWith("ALIVE")){
                                if (recordRoundNumber>latestRoundNumber){
                                    //THERE IS A NEW ROUND IN KAFKA
                                    LOGGER.info("Discard " + latestRoundNumber +", since there is a" +
                                            " new round in kafka log before the check record");
                                    latestRoundsJsCode = recordMessage;
                                    latestRoundNumber = recordRoundNumber;
                                }
                                else if(recordRoundNumber==latestRoundNumber){
                                    latestRoundsJsCode += recordMessage;
                                }
                                //RECORDS WITH ROUND NUMBERS LESS THAN latestRoundNumber CANNOT BE
                                // FOUND
                            }
                            //ALIVE RECORDS ARE NOT ADDED TO THE LATEST ROUND CODE
                        }
                    }
                    else if (!record.value().startsWith("CHECK,")){
                        //EVALUATING RECORDS OF ROUND TO WHICH NODE PARTICIPATED
                        String[] recordContent = record.value().split(",", 2);
                        int recordRoundNumber = Integer.parseInt(recordContent[0]);//Round number
                        // written with the record
                        String recordMessage = recordContent[1]; //ALIVE,nodeId or clean JS

                        if (this.joiningState == roundStatuses.FINISHED){
                            // NEWLY JOINED NODES WITH FINISHED STATE FIRST EXECUTE THIS
                            if (recordRoundNumber == this.roundNumber){
                                //RECORDS (HBs) WITH ROUND NUMBER AS proposedRoundNumber
                                LOGGER.info("Got HB of FINISHED round");
                                this.handleHeartbeat();
                            }
                            else if(recordRoundNumber == this.roundNumber + 1){
                                //SOMEONE HAS TIMEOUT BEFORE THIS NODE
                                LOGGER.info("Got new round message while in FINISHED state");
                                this.heartbeatListener.setLateToTimeout(true);
                                if (this.heartbeatListener.isAlive()){
                                    //TERMINATE LISTENER STARTED FOR FINISHED, TO MOVE TO NEW ROUND
                                    LOGGER.info("Late to timeout the round " + this.roundNumber);
                                    this.heartbeatListener.interrupt();
                                }
                                //WAIT UNTIL LISTENER IS FINISHED
                                this.heartbeatListener.join();
                                //CLEAN UPON THE FIRST (roundNumber + 1) RECORD
                                this.cleanRound(recordRoundNumber);
                                Value result = this.distributedConsensus.evaluateJsCode(recordMessage);
                                boolean consensusAchieved = this.onReceiving(result);
                                if (consensusAchieved) {
                                    this.onConsensus(result);
                                }
                                else{
                                    LOGGER.info("Leader for " + this.roundNumber +  " is not elected yet");
                                }
                            }
                            else{
                                LOGGER.error("Record with wrong round number while in FINISHED state");
                            }
                        }
                        else{
                            //NON-FINISHED STATE NODES IN ANY ROUND
                            if (recordRoundNumber == roundNumber + 1){
                                //CLEAN ALL ROUND RELATED DATA IN CONSENSUS APPLICATION WHEN THE
                                // FIRST MESSAGE TO LATEST ROUND COMES
                                this.heartbeatListener.join();
                                this.cleanRound(recordRoundNumber); //SETS THE ROUND NUMBER TO
                                // NEW RECORD'S ROUND NUMBERS
                            }
                            if(recordMessage.startsWith("ALIVE")){
                                if (this.roundNumber == recordRoundNumber){
                                    this.handleHeartbeat();
                                    LOGGER.info("Got HB");
                                }
                                else{
                                    LOGGER.error(getNodeId() + " :: Error: ALIVE with wrong round number");
                                    System.exit(-1);
                                }
                            }
                            else{
                                if(this.roundNumber == recordRoundNumber){
                                    LOGGER.info("Evaluating records of current round with round number : " +
                                            recordRoundNumber);
                                    Value result = this.distributedConsensus.evaluateJsCode(recordMessage);
                                    boolean consensusAchieved = this.onReceiving(result);
                                    if (consensusAchieved) {
                                        this.onConsensus(result);
                                    }
                                    else{
                                        LOGGER.info("Leader for " + this.roundNumber +  " is not elected yet");
                                    }
                                }
                                else{
                                    LOGGER.error("Error: Js record with wrong round number");
                                    System.exit(-1);
                                }
                            }
                        }
                    }
                }
            }
        } catch(Exception exception) {
            LOGGER.error("Exception occurred :", exception);
        }finally {
            this.distributedConsensus.closeConsumer();
        }
    }

    /**
     * Continuously write heartbeats
     */
    public void startHeartbeatSender(){
        LOGGER.info("Started sending HB");
        while (!this.terminate) {
            this.distributedConsensus.writeACommand(roundNumber + ",ALIVE,"+ getNodeId());
            LOGGER.info("wrote HB");
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                LOGGER.error("Leader was interrupted while sending HB :: " + java.time.LocalTime.now());
                e.printStackTrace();
            }
        }
    }

    /**
     * Start heartbeat listener thread
     */
    public void startHeartbeatListener(){
        LOGGER.info("Started HB listener");
        this.heartbeatListener = new HeartbeatListener(this);
        this.heartbeatListener.setName(getNodeId() + "_HBListener");
        this.heartbeatListener.start();
    }

    /**
     * Clean details of previous round when higher round number record is found
     * @param roundNumber round number to be set
     */
    public void cleanRound(int roundNumber){
        this.roundNumber  = roundNumber; //SET THE ROUND NUMBER TO NEW RECORD ROUND NUMBER
        this.setRuntimeJsCode(initialJsCode); // IN EACH NEW ROUND JS SHOULD BE RESET
        this.joiningState = null; //SHOULD BE DONE SINCE "FINISHED" NODES GET INTERRUPTED BY
        // MESSAGES UNTIL THEY CALL THEIR FIRST startNewRound()
        this.timeoutCounted = false;
        this.electedLeader = null;
        this.heartbeatListener.setLateToTimeout(false);
        LOGGER.info("Cleaned round attributes of round number " + (roundNumber -1));
    }

    /**
     * Participate to new round
     */
    public void startNewRound(){
        int nodeRank = (int)(1 + Math.random()*100);
        this.distributedConsensus.writeACommand((roundNumber+1) + ",if(!result.timeout){" +
                "nodeRanks.push({client:\""+ getNodeId() + "\",rank:" + nodeRank +"})};");
        LOGGER.info("Participated to new round "+ (roundNumber + 1) + "; my rank is " + nodeRank);
    }

    /**
     * Generate an uniques string
     * @return an unique string
     */
    public String generateUniqueKey(){
        String ALPHA_NUMERIC_STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder builder = new StringBuilder();
        int rawStringLength = 16;
        while (rawStringLength-- != 0) {
            int character = (int)(Math.random()*ALPHA_NUMERIC_STRING.length());
            builder.append(ALPHA_NUMERIC_STRING.charAt(character));
        }
        return builder.toString();
    }
}