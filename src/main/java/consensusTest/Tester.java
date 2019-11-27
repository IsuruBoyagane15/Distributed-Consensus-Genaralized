package consensusTest;

import distributedConsensus.ConsumerGenerator;
import distributedConsensus.LeaderCandidate;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.util.HashMap;
import java.util.Random;
import java.util.UUID;

/**
 * External class to start/kill LeaderCandidate threads and monitor the execution of leader elections
 */
public class Tester {

    private static final Logger LOGGER = Logger.getLogger(LeaderCandidate.class);

    private final String kafkaServerAddress, kafkaTopic,  initialJsCode, evaluationJsCode;
    private final Context jsContext;
    private final int maxProcessCount;
    private String runtimeJsCode, immortalProcess;
    private KafkaConsumer<String, String> kafkaConsumer;
    private boolean terminate, maxProcessCountReached;
    private HashMap<String, LeaderCandidate> activeProcesses;

    /**
     * Constructor
     *
     * @param kafkaServerAddress URL of Kafka server
     * @param kafkaTopic Kafka topic which LeaderCandidates communicate through
     * @param maxProcessCount Upper bound for number of parallel LeaderCandidate
     */
    public Tester(String kafkaServerAddress, String kafkaTopic, int maxProcessCount){
        this.kafkaTopic = kafkaTopic;
        this.kafkaServerAddress = kafkaServerAddress;
        this.kafkaConsumer = ConsumerGenerator.generateConsumer(kafkaServerAddress, kafkaTopic, "tester");
        this.jsContext = Context.create("js");
        this.immortalProcess = null;
        this.activeProcesses = new HashMap<>();
        this.terminate = false;
        this.maxProcessCountReached = false;
        this.maxProcessCount = maxProcessCount;
        this.initialJsCode = "var nodeRanks = [];result = {consensus:false, value:null, firstCandidate : null, timeout : false};";
        this.evaluationJsCode = "if(Object.keys(nodeRanks).length != 0){" +
                                    "result.firstCandidate = nodeRanks[0].client;" +
                        "}" +
                        "if(result.timeout){" +
                                    "result.consensus=true;" +
                                    "var leader = null;"+
                                    "var maxRank = 0;"+
                                    "for (var i = 0; i < nodeRanks.length; i++) {"+
                                        "if(nodeRanks[i].rank > maxRank){"+
                                            "result.value = nodeRanks[i].client;" +
                                            "maxRank = nodeRanks[i].rank;" +
                                        "}" +
                                    "}" +
                                "}" +
                        "result;";
        this.runtimeJsCode = initialJsCode;
    }

    /**
     * Consume the same Kafka log in which leader election happens and extract special states
     * in the election process
     */
    public void read(){
        Runnable consuming = () -> {
            int roundNumber = -1;
            try {
                while (!terminate) {
                    ConsumerRecords<String, String> records = kafkaConsumer.poll(10);
                    for (ConsumerRecord<String, String> record : records) {
                        if (!record.value().startsWith("CHECK")){
                            String[] recordContent = record.value().split(",", 2);
                            int recordNumber = Integer.parseInt(recordContent[0]);
                            String recordMessage = recordContent[1];
                            if(!recordMessage.startsWith("ALIVE")){
                                if (recordNumber > roundNumber){
                                    roundNumber = recordNumber;
                                    this.immortalProcess = jsContext.eval("js","result = {timeout : false}; var nodeRanks = [];" + recordMessage + "nodeRanks[0].client;").toString();
                                    LOGGER.info("Cannot kill " + this.immortalProcess + " for a while");
                                    runtimeJsCode = initialJsCode + recordMessage;
                                }
                                else{
                                    if (recordMessage.equals("result.timeout = true;")){
                                        LOGGER.info(this.immortalProcess + " can be killed from now on");
                                        this.immortalProcess = null;
                                    }
                                    runtimeJsCode = runtimeJsCode + recordMessage;
                                    Value result = jsContext.eval("js",runtimeJsCode + evaluationJsCode);
                                    boolean leaderElected = result.getMember("consensus").asBoolean();
                                    if (leaderElected){
                                        LOGGER.info("Leader for round number :" + roundNumber + " is " + result.getMember("value"));
                                    }
                                }
                            }
                        }
                    }
                }
            } catch(Exception exception) {
                LOGGER.error(exception.getStackTrace());
            }finally {
                kafkaConsumer.close();
            }
        };
        Thread consumer = new Thread(consuming);
        consumer.setName("tester_consumer");
        new Thread(consuming).start();
    }

    /**
     * Start a new LeaderCandidate thread
     *
     * @param kafkaServerAddress URL of Kafka server
     * @param kafkaTopic Kafka topic which LeaderCandidates communicate through
     */
    public void startNewProcess(String kafkaServerAddress, String kafkaTopic){
        String nodeId = UUID.randomUUID().toString();
        System.setProperty("id", nodeId);
        LOGGER.info("Id of the new process : " + nodeId);

        LeaderCandidate leaderCandidate = new LeaderCandidate(nodeId, initialJsCode, this.evaluationJsCode,
                kafkaServerAddress, kafkaTopic);

        Thread leaderCandidateThread = new Thread(leaderCandidate);
        leaderCandidateThread.setName(nodeId + "_consumer");
        leaderCandidateThread.start();
        this.activeProcesses.put(nodeId, leaderCandidate);
    }

    /**
     * stop a LeaderCandidate thread
     */
    public void killProcess(){
        Object[] nodeIds = activeProcesses.keySet().toArray();
        Object nodeId = nodeIds[new Random().nextInt(nodeIds.length)];
        LOGGER.info("Id of the process to be killed  : " + nodeId + " :: " + "Id of the immortal process : " + this.immortalProcess);
        if (nodeId.equals(this.immortalProcess)){
            LOGGER.info("Can't kill " + nodeId + " at this moment; Trying to kill again");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            killProcess();
        }
        else{
            LeaderCandidate leaderCandidateToBeKilled = activeProcesses.get(nodeId);
            activeProcesses.remove(nodeId);
            leaderCandidateToBeKilled.setTerminate(true);
            LOGGER.info("Killed " + nodeId);

            if(activeProcesses.size() == 0){
                this.terminate = true;
            }
        }
    }

    /**
     * Randomly start/kill LeaderCandidate threads until number of LeaderCandidate threads
     * equals maxProcessCount.
     * Only Kill LeaderCandidate threads after reaching maxProcessCount
     *
     * @param args kafkaServerAddress, KafkaTopic, maxProcessCount
     */
    public static void main(String[] args){
        Thread.currentThread().setName("tester_main");
        Tester tester = new Tester(args[0], args[1], Integer.parseInt(args[2]));
        tester.read();

        tester.startNewProcess(tester.kafkaServerAddress, tester.kafkaTopic);

        try {
            int sleepTime = (int)(1 + Math.random()*10)*1000;
            Thread.sleep(sleepTime);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        double factor = 0.25;
        while(!tester.terminate) {

            double rand = Math.random();
            if (rand > factor) {
                if (!tester.maxProcessCountReached) {
                    tester.startNewProcess(tester.kafkaServerAddress, tester.kafkaTopic);
                    if (tester.activeProcesses.size() == tester.maxProcessCount) {
                        tester.maxProcessCountReached = true;
                        factor = 1;
                    }
                } else {
                    LOGGER.info("Maximum Process count: " + tester.activeProcesses.size() + " is achieved.No more processes will start");
                }
            } else {
                tester.killProcess();
            }
            LOGGER.info("Number of leader candidates alive : " + tester.activeProcesses.size());

            int randWait = (int) (1 + Math.random() * 4) * 1000;
            try {
                Thread.sleep(randWait);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}