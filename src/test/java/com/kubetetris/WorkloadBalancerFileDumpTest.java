package com.kubetetris;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import com.kubetetris.balancer.WorkLoadBalancer;
import com.kubetetris.balancer.WorkLoadBalancerImpl;
import com.kubetetris.loadsimulator.NodeDataGenerator;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore
@RunWith(Parameterized.class)
public class WorkloadBalancerFileDumpTest {

    private static final Logger log = LoggerFactory.getLogger(WorkloadBalancerFileDumpTest.class);

    private static SystemController systemController;

    private static int iteration_count;

    private static BufferedWriter writer;

    private StringBuilder sb;

    @Parameterized.Parameters
    public static Object[][] data() {
        return new Object[100][0];
    }

    @BeforeClass
    public static void setup() throws IOException {
        writer = new BufferedWriter(new FileWriter("Validate_Algo.txt"));
        iteration_count = 0;
        writer.write("Recording results for validation for multiple runs...................\n");
    }

    @Before
    public void prepare() throws Exception{
        //Create SystemController and nodes and pods
        systemController = new SystemControllerImpl();
        List<Node> inputNodes = NodeDataGenerator.generate(3, 5);
        for(Node node: inputNodes)
            systemController.addNode(node);
        sb = new StringBuilder();
        sb.append("===============================================================Test Run : ").append(iteration_count).append("==================================================================\n");
        sb.append("Nodes information before balancing with entropy : ").append(systemController.getSystemEntropy()).append("\n");
        systemController.getNodes().forEach(n -> {
            sb.append(n).append("\n");
            sb.append(n.getPods()).append("\n");
        });
        writer.append(sb.toString());
    }

    @Test
    public void scenarioTest() throws Exception{
        WorkLoadBalancer workLoadBalancer = new WorkLoadBalancerImpl(systemController, 5);
        workLoadBalancer.balance();
        sb = new StringBuilder();
        sb.append("\nNodes information after balancing with entropy : ").append(systemController.getSystemEntropy()).append("\n");
        systemController.getNodes().forEach(n -> {
            sb.append(n).append("\n");
            sb.append(n.getPods()).append("\n");
        });
        writer.append(sb.toString());
        sb.append("=======================================================================================================================================================================\n");
        ++iteration_count;
    }
}