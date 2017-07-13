/*
 *    HoeffdingAdaptiveTree.java
 *    Copyright (C) 2008 University of Waikato, Hamilton, New Zealand
 *    @author Albert Bifet (abifet at cs dot waikato dot ac dot nz)
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package moa.classifiers.trees;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import moa.classifiers.bayes.NaiveBayes;
import moa.classifiers.core.AttributeSplitSuggestion;
import moa.classifiers.core.conditionaltests.InstanceConditionalTest;
import moa.classifiers.core.driftdetection.ADWIN;
import moa.classifiers.core.splitcriteria.SplitCriterion;
import moa.core.DoubleVector;
import moa.core.MiscUtils;
import moa.core.Utils;
import com.yahoo.labs.samoa.instances.Instance;

/**
 * Hoeffding Adaptive Tree for evolving data streams.
 *
 * <p>This adaptive Hoeffding Tree uses ADWIN to monitor performance of
 * branches on the tree and to replace them with new branches when their
 * accuracy decreases if the new branches are more accurate.</p>
 * See details in:</p>
 * <p>Adaptive Learning from Evolving Data Streams. Albert Bifet, Ricard Gavaldà.
 * IDA 2009</p>
 *
 * <ul>
 * <li> Same parameters as <code>HoeffdingTreeNBAdaptive</code></li>
 * <li> -l : Leaf prediction to use: MajorityClass (MC), Naive Bayes (NB) or NaiveBayes
 * adaptive (NBAdaptive).
 * </ul>
 *
 * @author Albert Bifet (abifet at cs dot waikato dot ac dot nz)
 * @version $Revision: 7 $
 */
public class CVFDT extends HoeffdingTree {

    private static final long serialVersionUID = 1L;

    private static long numInstances = 0;

    @Override
    public String getPurposeString() {
        return "Hoeffding Adaptive Tree for evolving data streams that uses ADWIN to replace branches for new ones.";
    }

 /*   public MultiChoiceOption leafpredictionOption = new MultiChoiceOption(
            "leafprediction", 'l', "Leaf prediction to use.", new String[]{
                "MC", "NB", "NBAdaptive"}, new String[]{
                "Majority class",
                "Naive Bayes",
                "Naive Bayes Adaptive"}, 2);*/

    public interface AdaNode {

        // Change for adwin
        //public boolean getErrorChange();
        public int numberLeaves();

        void setAlternateStatusForSubtreeNodes(boolean isAlternate);

		public double getErrorEstimation();

        public double getErrorWidth();

        public boolean isNullError();

        public void killTreeChilds(CVFDT ht);

        public void learnFromInstance(Instance inst, CVFDT ht, SplitNode parent, int parentBranch);

        public void filterInstanceToLeaves(Instance inst, SplitNode myparent, int parentBranch, List<FoundNode> foundNodes,
                boolean updateSplitterCounts);

        public boolean isAlternate();

        public void setAlternate(boolean isAlternate);

		public boolean isRoot();

		public void setRoot(boolean isRoot);

		public void setMainlineNode(AdaSplitNode parent);

		public AdaSplitNode getMainlineNode();

		public void setParent(AdaSplitNode parent);

		public AdaSplitNode getParent();

    }

    public static class AdaSplitNode extends SplitNode implements AdaNode {

        private static final long serialVersionUID = 1L;

        protected Node alternateTree;

        protected ADWIN estimationErrorWeight;
        //public boolean isAlternateTree = false;

        public boolean ErrorChange = false;

        protected int randomSeed = 1;

        protected Random classifierRandom;

        private boolean isAlternate = false;

        private boolean isRoot = false;

		private AdaSplitNode mainlineNode = null; //null by default unless there is an attachment point

		private AdaSplitNode parent = null;

		@Override
		public void setParent(AdaSplitNode parent) {
			this.parent = parent;

		}

		@Override
		public AdaSplitNode getParent() {
			return this.parent;
		}


		@Override
		public boolean isAlternate() {
			return this.isAlternate;
		}

		@Override
		public void setAlternate(boolean isAlternate) {
			this.isAlternate = isAlternate;
		}



        //public boolean getErrorChange() {
        //		return ErrorChange;
        //}
        @Override
        public int calcByteSizeIncludingSubtree() {
            int byteSize = calcByteSize();
            if (alternateTree != null) {
                byteSize += alternateTree.calcByteSizeIncludingSubtree();
            }
            if (estimationErrorWeight != null) {
                byteSize += estimationErrorWeight.measureByteSize();
            }
            for (Node child : this.children) {
                if (child != null) {
                    byteSize += child.calcByteSizeIncludingSubtree();
                }
            }
            return byteSize;
        }

        public AdaSplitNode(InstanceConditionalTest splitTest,
                double[] classObservations, int size, boolean isAlternate) {
            super(splitTest, classObservations, size);
            this.classifierRandom = new Random(this.randomSeed);
            this.setAlternate(isAlternate);
        }

        public AdaSplitNode(InstanceConditionalTest splitTest,
                double[] classObservations, boolean isAlternate) {
            super(splitTest, classObservations);
            this.classifierRandom = new Random(this.randomSeed);
            this.setAlternate(isAlternate);
        }

        public AdaSplitNode(InstanceConditionalTest splitTest,
                double[] classObservations, int size) {
            super(splitTest, classObservations, size);
            this.classifierRandom = new Random(this.randomSeed);
        }

        public AdaSplitNode(InstanceConditionalTest splitTest,
                double[] classObservations) {
            super(splitTest, classObservations);
            this.classifierRandom = new Random(this.randomSeed);
        }
        @Override
        public int numberLeaves() {
            int numLeaves = 0;
            for (Node child : this.children) {
                if (child != null) {
                    numLeaves += ((AdaNode) child).numberLeaves();
                }
            }
            return numLeaves;
        }

        @Override
        public double getErrorEstimation() {
            return this.estimationErrorWeight.getEstimation();
        }

        @Override
        public double getErrorWidth() {
            double w = 0.0;
            if (isNullError() == false) {
                w = this.estimationErrorWeight.getWidth();
            }
            return w;
        }

        @Override
        public boolean isNullError() {
            return (this.estimationErrorWeight == null);
        }

        // SplitNodes can have alternative trees, but LearningNodes can't
        // LearningNodes can split, but SplitNodes can't
        // Parent nodes are allways SplitNodes
        @Override
        public void learnFromInstance(Instance inst, CVFDT ht, SplitNode parent, int parentBranch) {

            System.out.println("Main Tree is of depth " + ht.treeRoot.subtreeDepth());


            int trueClass = (int) inst.classValue();
            //New option vore
            int k = MiscUtils.poisson(1.0, this.classifierRandom);
            Instance weightedInst = inst.copy();
            if (k > 0) {
                //weightedInst.setWeight(inst.weight() * k);
            }
            //Compute ClassPrediction using filterInstanceToLeaf
            //int ClassPrediction = Utils.maxIndex(filterInstanceToLeaf(inst, null, -1).node.getClassVotes(inst, ht));
            int ClassPrediction = 0;
            Node leaf = filterInstanceToLeaf(inst, this.getParent(), parentBranch).node;
            if (leaf != null) {
                ClassPrediction = Utils.maxIndex(leaf.getClassVotes(inst, ht));
            }

            boolean blCorrect = (trueClass == ClassPrediction);

            if (this.estimationErrorWeight == null) {
                this.estimationErrorWeight = new ADWIN();
            }
            double oldError = this.getErrorEstimation();
            this.ErrorChange = this.estimationErrorWeight.setInput(blCorrect == true ? 0.0 : 1.0);
            if (this.ErrorChange == true && oldError > this.getErrorEstimation()) {
                //if error is decreasing, don't do anything
                this.ErrorChange = false;
            }

            // Check condition to build a new alternate tree
            if (this.ErrorChange && !this.isAlternate()) {// disabling alternates of alternates

                //Start a new alternative tree : learning node
                this.alternateTree = ht.newLearningNode(true); // isAlternate is set to true
                ((AdaNode)this.alternateTree).setMainlineNode(this); // this node is the alternate's attachment point
                ((AdaNode)this.alternateTree).setParent(this.getParent());
                ht.alternateTrees++;
            } // Check condition to replace tree

            else if (this.alternateTree != null && ((AdaNode) this.alternateTree).isNullError() == false) {
                if (this.getErrorWidth() > 300 && ((AdaNode) this.alternateTree).getErrorWidth() > 300) {
                    double oldErrorRate = this.getErrorEstimation();
                    double altErrorRate = ((AdaNode) this.alternateTree).getErrorEstimation();
                    double fDelta = .05;
                    //if (gNumAlts>0) fDelta=fDelta/gNumAlts;
                    double fN = 1.0 / (((AdaNode) this.alternateTree).getErrorWidth()) + 1.0 / (this.getErrorWidth());
                    double Bound = Math.sqrt(2.0 * oldErrorRate * (1.0 - oldErrorRate) * Math.log(2.0 / fDelta) * fN);

//                    System.out.print(this.alternateTree.subtreeDepth()
//                    		+ " " + this.subtreeDepth() +
//                    		" " + this.isRoot() +
//                    		" " + this.isAlternate());
//
//                    if(this.getParent() == null){
//                    	System.out.print(" ||parent is null; root level node||");
//                    }
//
//                    System.out.println();


                    if (Bound < oldErrorRate - altErrorRate
                    		  //&& this.subtreeDepth() < 4
                    		) {
                        //System.out.println("Main Tree is of depth " + ht.treeRoot.subtreeDepth());

                        // Switch alternate tree
                        ht.activeLeafNodeCount -= this.numberLeaves();
                        ht.activeLeafNodeCount += ((AdaNode) this.alternateTree).numberLeaves();
                        this.killTreeChilds(ht);
                        ((AdaNode)this.alternateTree).setAlternateStatusForSubtreeNodes(false);
                        ((AdaNode)(this.alternateTree)).setMainlineNode(null);


                        if (!this.isRoot()) {
                            this.getParent().setChild(parentBranch, this.alternateTree);
                        	((AdaNode)(this.alternateTree)).setRoot(false);
                            ((AdaNode)this.alternateTree).setParent(this.getParent());
                            //((AdaSplitNode) parent.getChild(parentBranch)).alternateTree = null;
                        } else {
                            // Switch root tree
                        	((AdaNode)(this.alternateTree)).setRoot(true);
                        	((AdaNode)(this.alternateTree)).setParent(null);
                            ht.treeRoot = this.alternateTree;
                        }
                        this.alternateTree = null;
                        ht.switchedAlternateTrees++;
                    } else if (Bound < altErrorRate - oldErrorRate) {
                        // Erase alternate tree
                        if (this.alternateTree instanceof ActiveLearningNode) {
                            this.alternateTree = null;
                            //ht.activeLeafNodeCount--;
                        } else if (this.alternateTree instanceof InactiveLearningNode) {
                            this.alternateTree = null;
                            //ht.inactiveLeafNodeCount--;
                        } else {
                            ((AdaSplitNode) this.alternateTree).killTreeChilds(ht);
                        }
                        ht.prunedAlternateTrees++;
                    }
                }
            }
            //}
            //learnFromInstance alternate Tree and Child nodes
            if (this.alternateTree != null) {
                ((AdaNode) this.alternateTree).learnFromInstance(weightedInst, ht, this.getParent(), parentBranch);
            }
            int childBranch = this.instanceChildIndex(inst);
            Node child = this.getChild(childBranch);
            if (child != null) {
                ((AdaNode) child).learnFromInstance(weightedInst, ht, this, childBranch);
            }
        }

		@Override
        public void setAlternateStatusForSubtreeNodes(boolean isAlternate) {

          this.setAlternate(isAlternate);

          for (Node child : this.children) {
            if (child != null) {
              ((AdaNode)child).setAlternateStatusForSubtreeNodes(isAlternate);
            }
          }
        }



        @Override
        public void killTreeChilds(CVFDT ht) {
            for (Node child : this.children) {
                if (child != null) {
                    //Delete alternate tree if it exists
                    if (child instanceof AdaSplitNode && ((AdaSplitNode) child).alternateTree != null) {
                        ((AdaNode) ((AdaSplitNode) child).alternateTree).killTreeChilds(ht);
                        ht.prunedAlternateTrees++;
                    }
                    //Recursive delete of SplitNodes
                    if (child instanceof AdaSplitNode) {
                        ((AdaNode) child).killTreeChilds(ht);
                    }
                    if (child instanceof ActiveLearningNode) {
                        child = null;
                        ht.activeLeafNodeCount--;
                    } else if (child instanceof InactiveLearningNode) {
                        child = null;
                        ht.inactiveLeafNodeCount--;
                    }
                }
            }
        }

        //New for option votes
        //@Override
        @Override
		public void filterInstanceToLeaves(Instance inst, SplitNode myparent,
                int parentBranch, List<FoundNode> foundNodes,
                boolean updateSplitterCounts) {
            if (updateSplitterCounts) {
                this.observedClassDistribution.addToValue((int) inst.classValue(), inst.weight());
            }
            int childIndex = instanceChildIndex(inst);
            if (childIndex >= 0) {
                Node child = getChild(childIndex);
                if (child != null) {
                    ((AdaNode) child).filterInstanceToLeaves(inst, this, childIndex,
                            foundNodes, updateSplitterCounts);
                    // this will usually just take you down one path until you hit a learning node. Unless you are overextending
                    // your tree without pruning
                } else {
                    foundNodes.add(new FoundNode(null, this, childIndex));
                    // Only killTreeChilds would create null child nodes
                }
            }
            if (this.alternateTree != null) {
                ((AdaNode) this.alternateTree).filterInstanceToLeaves(inst, this, -999, foundNodes, updateSplitterCounts);
                // the -999 used to launch this subtree filter becomes inutile immediately following
                // the top node of the subtree. Only the immediate children of a split will see this as a parentBranch
                // So a foundnode created further down cannot be distinguished from the mainline thing
                // Using this to separate out the alternate found nodes from the mainline ones won't work.
                // But that is how it seems to be used...

            }
        }

		@Override
		public boolean isRoot() {
			return this.isRoot;
		}

		@Override
		public void setRoot(boolean isRoot) {
			this.isRoot = isRoot;

		}

		@Override
		public void setMainlineNode(AdaSplitNode mainlineNode) {
			this.mainlineNode  = mainlineNode;
		}

		@Override
		public AdaSplitNode getMainlineNode() {
			return this.mainlineNode;
		}
    }

    public static class AdaLearningNode extends LearningNodeNBAdaptive implements AdaNode {

        private static final long serialVersionUID = 1L;

        protected ADWIN estimationErrorWeight;

        public boolean ErrorChange = false;

        protected int randomSeed = 1;

        protected Random classifierRandom;

        private boolean isAlternate = false;

		private boolean isRoot = false;

		private AdaSplitNode mainlineNode = null; //null by default unless there is an attachment point

		private AdaSplitNode parent = null;

		@Override
		public void setParent(AdaSplitNode parent) {
			this.parent = parent;

		}

		@Override
		public AdaSplitNode getParent() {
			return this.parent;
		}


		@Override
		public boolean isAlternate() {
			return this.isAlternate;
		}

		@Override
		public void setAlternate(boolean isAlternate) {
			this.isAlternate = isAlternate;
		}

        @Override
        public int calcByteSize() {
            int byteSize = super.calcByteSize();
            if (estimationErrorWeight != null) {
                byteSize += estimationErrorWeight.measureByteSize();
            }
            return byteSize;
        }

        public AdaLearningNode(double[] initialClassObservations) {
            super(initialClassObservations);
            this.classifierRandom = new Random(this.randomSeed);
        }

        public AdaLearningNode(double[] initialClassObservations, boolean isAlternate) {
            super(initialClassObservations);
            this.classifierRandom = new Random(this.randomSeed);
            this.setAlternate(isAlternate);
        }

        @Override
        public int numberLeaves() {
            return 1;
        }

        @Override
        public double getErrorEstimation() {
            if (this.estimationErrorWeight != null) {
                return this.estimationErrorWeight.getEstimation();
            } else {
                return 0;
            }
        }

        @Override
        public double getErrorWidth() {
            return this.estimationErrorWeight.getWidth();
        }

        @Override
        public boolean isNullError() {
            return (this.estimationErrorWeight == null);
        }

        @Override
        public void killTreeChilds(CVFDT ht) {
        }

        @Override
        public void learnFromInstance(Instance inst, CVFDT ht, SplitNode parent, int parentBranch) {
//
//        	if(!this.isAlternate()){
//        		System.err.println(numInstances);
//        		// this shows mainline learning nodes stop learning once drift occurs
//        	}

            int trueClass = (int) inst.classValue();
            //New option vore
            int k = MiscUtils.poisson(1.0, this.classifierRandom);
            Instance weightedInst = inst.copy();
            //if (k > 0 && this.isAlternate()) {
            	// use weighted instance if necessary for asymmetric alternate weighting
                //weightedInst.setWeight(inst.weight() * k);
                // this wasn't in the paper
            //}
            //Compute ClassPrediction using filterInstanceToLeaf
            int ClassPrediction = Utils.maxIndex(this.getClassVotes(inst, ht));

            boolean blCorrect = (trueClass == ClassPrediction);

            if (this.estimationErrorWeight == null) {
                this.estimationErrorWeight = new ADWIN();
            }
            double oldError = this.getErrorEstimation();
            this.ErrorChange = this.estimationErrorWeight.setInput(blCorrect == true ? 0.0 : 1.0);
            if (this.ErrorChange == true && oldError > this.getErrorEstimation()) {
                this.ErrorChange = false;
            }

            //Update statistics
            learnFromInstance(weightedInst, ht);	//inst

            //Check for Split condition
            double weightSeen = this.getWeightSeen();
            if (weightSeen
                    - this.getWeightSeenAtLastSplitEvaluation() >= ht.gracePeriodOption.getValue()) {
                ht.attemptToSplit(this, this.getParent(),
                        parentBranch);
                this.setWeightSeenAtLastSplitEvaluation(weightSeen);
            }


            //learnFromInstance alternate Tree and Child nodes
			/*if (this.alternateTree != null)  {
            this.alternateTree.learnFromInstance(inst,ht);
            }
            for (Node child : this.children) {
            if (child != null) {
            child.learnFromInstance(inst,ht);
            }
            }*/
        }

        @Override
        public double[] getClassVotes(Instance inst, HoeffdingTree ht) {
            double[] dist;
            int predictionOption = ((CVFDT) ht).leafpredictionOption.getChosenIndex();
            if (predictionOption == 0) { //MC
                dist = this.observedClassDistribution.getArrayCopy();
            } else if (predictionOption == 1) { //NB
                dist = NaiveBayes.doNaiveBayesPrediction(inst,
                        this.observedClassDistribution, this.attributeObservers);
            } else { //NBAdaptive
                if (this.mcCorrectWeight > this.nbCorrectWeight) {
                    dist = this.observedClassDistribution.getArrayCopy();
                } else {
                    dist = NaiveBayes.doNaiveBayesPrediction(inst,
                            this.observedClassDistribution, this.attributeObservers);
                }
            }
            //New for option votes
            double distSum = Utils.sum(dist);
            if (distSum * this.getErrorEstimation() * this.getErrorEstimation() > 0.0) {
                Utils.normalize(dist, distSum * this.getErrorEstimation() * this.getErrorEstimation()); //Adding weight
            }
            return dist;
        }

        //New for option votes
        @Override
        public void filterInstanceToLeaves(Instance inst,
                SplitNode splitparent, int parentBranch,
                List<FoundNode> foundNodes, boolean updateSplitterCounts) {

            foundNodes.add(new FoundNode(this, splitparent, parentBranch));
        }

		@Override
		public boolean isRoot() {
			return this.isRoot ;
		}

		@Override
		public void setRoot(boolean isRoot) {
			this.isRoot = isRoot;

		}
		@Override
		public void setMainlineNode(AdaSplitNode mainlineNode) {
			this.mainlineNode  = mainlineNode;
		}

		@Override
		public AdaSplitNode getMainlineNode() {
			return this.mainlineNode;
		}

		@Override
		public void setAlternateStatusForSubtreeNodes(boolean isAlternate) {
			this.setAlternate(isAlternate);
		}

    }

    protected int alternateTrees;

    protected int prunedAlternateTrees;

    protected int switchedAlternateTrees;


    protected LearningNode newLearningNode(boolean isAlternate) {
        return new AdaLearningNode(new double[0], isAlternate);
    }
    protected LearningNode newLearningNode(double[] initialClassObservations, boolean isAlternate) {
        return new AdaLearningNode(initialClassObservations, isAlternate);
    }

    @Override
    protected LearningNode newLearningNode(double[] initialClassObservations) {
        // IDEA: to choose different learning nodes depending on predictionOption
        return new AdaLearningNode(initialClassObservations);
    }

    protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations, int size, boolean isAlternate) {
    	return new AdaSplitNode(splitTest, classObservations, size, isAlternate);
    }

	protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations, boolean isAlternate) {
    	return new AdaSplitNode(splitTest, classObservations, isAlternate);
    	}

   @Override
    protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations, int size) {
        return new AdaSplitNode(splitTest, classObservations, size);
    }

    @Override
    protected SplitNode newSplitNode(InstanceConditionalTest splitTest,
            double[] classObservations) {
        return new AdaSplitNode(splitTest, classObservations);
    }

    @Override
    public void trainOnInstanceImpl(Instance inst) {
        if (this.treeRoot == null) {
            this.treeRoot = newLearningNode(false); // root cannot be alternate
            ((AdaNode) this.treeRoot).setRoot(true);
            ((AdaNode) this.treeRoot).setParent(null);
            this.activeLeafNodeCount = 1;
        }
        ((AdaNode) this.treeRoot).learnFromInstance(inst, this, null, -1);
    }

    //New for options vote
    public FoundNode[] filterInstanceToLeaves(Instance inst,
            SplitNode parent, int parentBranch, boolean updateSplitterCounts) {
        List<FoundNode> nodes = new LinkedList<FoundNode>();
        ((AdaNode) this.treeRoot).filterInstanceToLeaves(inst, parent, parentBranch, nodes,
                updateSplitterCounts);
        return nodes.toArray(new FoundNode[nodes.size()]);
    }

    @Override
	protected void attemptToSplit(ActiveLearningNode node, SplitNode parent,
            int parentIndex) {
        if (!node.observedClassDistributionIsPure()) {
            SplitCriterion splitCriterion = (SplitCriterion) getPreparedClassOption(this.splitCriterionOption);
            AttributeSplitSuggestion[] bestSplitSuggestions = node.getBestSplitSuggestions(splitCriterion, this);
            Arrays.sort(bestSplitSuggestions);
            boolean shouldSplit = false;
            if (bestSplitSuggestions.length < 2) {
                shouldSplit = bestSplitSuggestions.length > 0;
            } else {
                double hoeffdingBound = computeHoeffdingBound(splitCriterion.getRangeOfMerit(node.getObservedClassDistribution()),
                        this.splitConfidenceOption.getValue(), node.getWeightSeen());
                AttributeSplitSuggestion bestSuggestion = bestSplitSuggestions[bestSplitSuggestions.length - 1];
                AttributeSplitSuggestion secondBestSuggestion = bestSplitSuggestions[bestSplitSuggestions.length - 2];
                if ((bestSuggestion.merit - secondBestSuggestion.merit > hoeffdingBound)
                        || (hoeffdingBound < this.tieThresholdOption.getValue())) {
                    shouldSplit = true;
                }
                // }
                if ((this.removePoorAttsOption != null)
                        && this.removePoorAttsOption.isSet()) {
                    Set<Integer> poorAtts = new HashSet<Integer>();
                    // scan 1 - add any poor to set
                    for (int i = 0; i < bestSplitSuggestions.length; i++) {
                        if (bestSplitSuggestions[i].splitTest != null) {
                            int[] splitAtts = bestSplitSuggestions[i].splitTest.getAttsTestDependsOn();
                            if (splitAtts.length == 1) {
                                if (bestSuggestion.merit
                                        - bestSplitSuggestions[i].merit > hoeffdingBound) {
                                    poorAtts.add(new Integer(splitAtts[0]));
                                }
                            }
                        }
                    }
                    // scan 2 - remove good ones from set
                    for (int i = 0; i < bestSplitSuggestions.length; i++) {
                        if (bestSplitSuggestions[i].splitTest != null) {
                            int[] splitAtts = bestSplitSuggestions[i].splitTest.getAttsTestDependsOn();
                            if (splitAtts.length == 1) {
                                if (bestSuggestion.merit
                                        - bestSplitSuggestions[i].merit < hoeffdingBound) {
                                    poorAtts.remove(new Integer(splitAtts[0]));
                                }
                            }
                        }
                    }
                    for (int poorAtt : poorAtts) {
                        node.disableAttribute(poorAtt);
                    }
                }
            }
            if (shouldSplit) {
                AttributeSplitSuggestion splitDecision = bestSplitSuggestions[bestSplitSuggestions.length - 1];
                if (splitDecision.splitTest == null) {
                    // preprune - null wins
                    deactivateLearningNode(node, ((AdaNode)node).getParent(), parentIndex);
                } else {
                    SplitNode newSplit = newSplitNode(splitDecision.splitTest,
                            node.getObservedClassDistribution(),splitDecision.numSplits(), ((AdaNode)(node)).isAlternate());
                    for (int i = 0; i < splitDecision.numSplits(); i++) {
                        Node newChild = newLearningNode(splitDecision.resultingClassDistributionFromSplit(i), ((AdaNode)newSplit).isAlternate());
                        ((AdaNode)newChild).setParent((AdaSplitNode)newSplit);
                        newSplit.setChild(i, newChild);
                    }
                    this.activeLeafNodeCount--;
                    this.decisionNodeCount++;
                    this.activeLeafNodeCount += splitDecision.numSplits();
                    if (((AdaNode)node).isRoot()) {
                    	((AdaNode)newSplit).setRoot(true);
                    	((AdaNode)newSplit).setParent(null);
                        this.treeRoot = newSplit;
                    }
                    else if (((AdaNode)node).getMainlineNode() != null) { // if the node happens to have a mainline attachment, i.e it is alternate
                    	((AdaNode)node).getMainlineNode().alternateTree = newSplit;
                    	((AdaNode)newSplit).setParent(((AdaNode)node).getParent());
                    }
                    else { //if the node is neither root nor an alternate, it must have a mainline split parent
                    	((AdaNode)node).getParent().setChild(parentIndex, newSplit);
                    	((AdaNode)newSplit).setParent(((AdaNode)node).getParent());
                    }
                }
                // manage memory
                enforceTrackerLimit();
            }
        }
    }

    @Override
    public double[] getVotesForInstance(Instance inst) {
    	if (this.treeRoot != null) {
    		numInstances++;
    		FoundNode[] foundNodes = filterInstanceToLeaves(inst,
    				null, -1, false);
    		DoubleVector result = new DoubleVector();
    		int predictionPaths = 0;
    		for (FoundNode foundNode : foundNodes) {

    					Node leafNode = foundNode.node;
    					if (leafNode == null) {
    						leafNode = foundNode.parent;
    					}
    					double[] dist = leafNode.getClassVotes(inst, this);

    					if(!((AdaNode)leafNode).isAlternate()){

    						result.addValues(dist);

    					}

    					predictionPaths++;

    					return result.getArrayRef();

    		}

    	}
    	return new double[0];
    }
}