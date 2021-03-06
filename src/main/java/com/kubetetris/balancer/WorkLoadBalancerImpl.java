package com.kubetetris.balancer;

import com.kubetetris.interaction.KubernetesAccessor;
import com.kubetetris.interaction.KubernetesAccessorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.kubetetris.SystemController;
import com.kubetetris.Node;
import com.kubetetris.Pod;

import javax.inject.Singleton;

@Singleton
public class WorkLoadBalancerImpl implements WorkLoadBalancer{

    private static final Logger log = LoggerFactory.getLogger(WorkLoadBalancerImpl.class);

    private static int currIterations = 1;

    private KubernetesAccessor kubernetesAccessor;
    private SystemController controller;
    private int ITERATIONS;

    public WorkLoadBalancerImpl(SystemController controller, int iterations){
        this.ITERATIONS = iterations;
        this.controller = controller;
        this.kubernetesAccessor = new KubernetesAccessorImpl();
    }

    // check if in the system all are on each side of pivot ratio
    private boolean isSchedulingDone(double pivotRatio){
        List<Double> values = new ArrayList<>();
        controller.getNodes().forEach(node ->{
            values.add(pivotRatio - node.getAvailableCapacity().getCpuMemoryRatio());
        });
        boolean result = values.stream().filter(Objects::nonNull).allMatch(i -> i < 0) || values.stream().filter(Objects::nonNull).allMatch(i -> i > 0);

        if(result){
            log.info("all the nodes are on one side of the pivot ratio. No mode balancing of CPU/Memory possible");
        }
        return result;
    }

    private boolean swapIfPossible(Node nodeA, Node nodeB, Pod podA, Pod podB ){

        double entropyBeforeSwap = controller.getSystemEntropy();
        double entropyAfterSwap;

        boolean aRemovedFromA = nodeA.removePod(podA);
        boolean bRemovedFromB = nodeB.removePod(podB);

        if(aRemovedFromA && bRemovedFromB) {
            boolean bAddedToA = nodeA.addPod(podB);
            boolean aAddedToB = nodeB.addPod(podA);

            if (bAddedToA && aAddedToB) {
                //Swap is successful, check for entropy again
                entropyAfterSwap = controller.getSystemEntropy();
                if (entropyAfterSwap >= entropyBeforeSwap) {
                    //Revert swapping
                    if (nodeA.removePod(podB) && nodeB.removePod(podA)) {
                        if (nodeA.addPod(podA) && nodeB.addPod(podB)) {
                            log.debug("Swap ignored since new system entropy {} will be greater than old {}", entropyAfterSwap, entropyBeforeSwap);
                        } else {
                            log.debug("Swap revert failed do not proceed further and exit");
                        }
                    }
                    return false;
                }
                System.out.println("Swapping pod " + podA.getName() +" running on node" + nodeA.getName() + " with pod " + podB.getName() + " running on node " + nodeB.getName());
                kubernetesAccessor.swapPods(podA.getName(), nodeA.getName(), podB.getName(), nodeB.getName() );

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                log.debug("swap is successful for node {} , pod {} and node {} , pod {}" ,nodeA.getName(), podB.getName(), nodeB.getName(), podA.getName());
                log.debug("Swap is successful and entropy changed from {} to {}", entropyBeforeSwap, entropyAfterSwap);

                System.out.println("Swap is successful and entropy changed from " + entropyBeforeSwap+ " to "+ entropyAfterSwap);

                return true;
            } else if(bAddedToA){
                nodeA.removePod(podB);
                if (nodeA.addPod(podA) && nodeB.addPod(podB)) {
                    log.debug("Swap reverted...");
                }
                return false;
            } else if(aAddedToB){
                nodeB.removePod(podA);
                if (nodeA.addPod(podA) && nodeB.addPod(podB)) {
                    log.debug("Swap reverted...");
                }
                return false;
            }
            else {
                log.debug("Swap failed since available capacity was less");
                if (nodeA.addPod(podA) && nodeB.addPod(podB)) {
                    log.debug("Swap reverted...");
                }
                return false;
            }
        } else if(aRemovedFromA){
            if(nodeA.addPod(podA))
                log.debug("Swap reverted...");
            return false;
        } else if(bRemovedFromB){
            if(nodeB.addPod(podB))
                log.debug("Swap reverted...");
            return false;
        } else{
            return false;
        }
    }

    // actual scheduler
    public void balance(){
        double pivotRatio = controller.getPivotRatio();
        double entropyBeforeBalancing = controller.getSystemEntropy();
        System.out.print("Nodes information before balancing : ");
        controller.getNodes().forEach(n -> {
            System.out.print(" " + n);
            System.out.print(" " + n.getPods());
        });

        while (currIterations <= ITERATIONS && !isSchedulingDone(pivotRatio)){

            log.debug("This is the {} iteration" , currIterations);

            List<Node> sortedNodes = controller.getNodesSortedByRatio();
            int left=0,right = sortedNodes.size()-1;

            //Recompute the distance for both the nodes because after swap available capacity might have changed
            double leftNodeDistance = sortedNodes.get(left).getDistanceFromPivot(pivotRatio);
            double rightNodeDistance = sortedNodes.get(right).getDistanceFromPivot(pivotRatio);

            while (sortedNodes.get(left).getAvailableCapacity().getCpuMemoryRatio() < pivotRatio && sortedNodes.get(right).getAvailableCapacity().getCpuMemoryRatio() > pivotRatio){

                //Left side of pivot has cpu intensive pods so node has more memory available
                // We will pick pod with max cpu for balancing
                List<Pod> podsCpuSorted = sortedNodes.get(left).getPodsSortedByCpu();

                //Right side of pivot has memory intensive pods so node has more cpu available
                // We will pick pod with max memory for balancing
                List<Pod> podsMemSorted = sortedNodes.get(right).getPodsSortedByMem();

                int leftPods = 0;
                int rightPods = 0;

                boolean isSwapped = false;
                while(leftPods < podsCpuSorted.size() && rightPods < podsMemSorted.size()){

                    isSwapped = swapIfPossible(sortedNodes.get(left), sortedNodes.get(right), podsCpuSorted.get(leftPods), podsMemSorted.get(rightPods));

                    if(isSwapped){
                        break;
                    } else{
                        log.debug("swap ignored for node {} , pod {} and node {} , pod {}" ,sortedNodes.get(left), podsCpuSorted.get(leftPods), sortedNodes.get(right), podsMemSorted.get(rightPods));
                    }

                    if (leftNodeDistance < rightNodeDistance){
                        if(leftPods<podsCpuSorted.size()-1){
                            leftPods++;
                        }else{
                            leftPods=0;
                            rightPods++;
                        }
                    }else{
                        if(rightPods<podsMemSorted.size()-1){
                            rightPods++;
                        }else{
                            rightPods=0;
                            leftPods++;
                        }
                    }
                }

                if(isSwapped){
                    break;
                }

                if(leftNodeDistance < rightNodeDistance){
                    if(sortedNodes.get(left+1).getAvailableCapacity().getCpuMemoryRatio() < pivotRatio ){
                        left++;
                    }else{
                        left=0;
                        right--;
                    }
                }else{
                    if(sortedNodes.get(right-1).getAvailableCapacity().getCpuMemoryRatio() > pivotRatio ){
                        right--;
                    }else{
                        right=sortedNodes.size()-1;
                        left++;
                    }
                }

            }
            currIterations++;
        }
        double entropyAfterBalancing = controller.getSystemEntropy();
        System.out.println("System entropy before  balancing : "+ entropyBeforeBalancing);
        System.out.println("System entropy after  balancing : "+ entropyAfterBalancing);
        System.out.print("Nodes information after balancing : ");
        controller.getNodes().forEach(n -> {
            System.out.print(" "+ n);
            System.out.print(" "+ n.getPods());
        });
    }
}
