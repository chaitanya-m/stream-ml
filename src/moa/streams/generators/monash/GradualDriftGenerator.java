package moa.streams.generators.monash;

import java.util.List;

import moa.core.Example;
import moa.core.FastVector;
import moa.core.InstanceExample;
import moa.core.ObjectRepository;
import moa.streams.InstanceStream;
import moa.tasks.TaskMonitor;

import org.apache.commons.math3.random.JDKRandomGenerator;
import org.apache.commons.math3.random.RandomDataGenerator;
import org.apache.commons.math3.random.RandomGenerator;

import com.github.javacliparser.FloatOption;
import com.github.javacliparser.IntOption;
import com.yahoo.labs.samoa.instances.Attribute;
import com.yahoo.labs.samoa.instances.DenseInstance;
import com.yahoo.labs.samoa.instances.Instance;
import com.yahoo.labs.samoa.instances.Instances;
import com.yahoo.labs.samoa.instances.InstancesHeader;

public class GradualDriftGenerator extends DriftGenerator{

	private static final long serialVersionUID = -3513131640712137498L;

	public FloatOption driftMagnitudePrior = new FloatOption("driftMagnitudePrior", 'i',
			"Magnitude of the drift between the starting probability and the one after the drift."
					+ " Magnitude is expressed as the Hellinger or Total Variation distance [0,1]", 0.5, 1e-20, 0.9);

	public IntOption burnInNInstances = new IntOption("burnInNInstances", 'b',
			"Number of instances before the start of the drift", 10000, 0, Integer.MAX_VALUE);

	public IntOption driftDuration = new IntOption("driftDuration", 'd',
			"How long drift lasts", 10000, 0, Integer.MAX_VALUE);

	public GradualDriftGenerator() {
		super();

	}

	/* TODO: Do we really need a serializable object, and to set the UID
	 * explicitly rather than let JDK handle it?*/

	protected InstancesHeader streamHeader;

	/**
	 * p(x) before drift
	 */
	double[][] pxbd;
	/**
	 * p(y|x) before drift
	 */
	double[][] pygxbd;

	/**
	 * p(x) after drift
	 */
	double[][] pxad;

	/**
	 * Are the cells drifting up or down?
	 */
	double cellDirection[][];

	RandomDataGenerator r;

	long nInstancesGeneratedSoFar;

	// Do we need implementations for these?

	@Override
	public long estimatedRemainingInstances() {
		return -1;
	}

	@Override
	public boolean hasMoreInstances() {
		return true;
	}

	@Override
	public boolean isRestartable() {
		return true;
	}

	@Override
	public void restart() {
		nInstancesGeneratedSoFar = 0L;
	}

	@Override
	public void getDescription(StringBuilder sb, int indent) {

	}

	@Override
	public String getPurposeString() {
		return "Generates a stream with an abrupt drift of given magnitude.";
	}

	@Override
	public InstancesHeader getHeader() {
		return streamHeader;
	}

	protected void generateHeader() {

		FastVector<Attribute> attributes = getHeaderAttributes(nAttributes
				.getValue(), nValuesPerAttribute.getValue());

		this.streamHeader = new InstancesHeader(new Instances(
				getCLICreationString(InstanceStream.class), attributes, 0));
		this.streamHeader.setClassIndex(this.streamHeader.numAttributes() - 1);
	}

	@Override
	public InstanceExample nextInstance() {
		// Put px gradual drift interpolation here
		// i.e. actually generate intermediate px distributions here
		// double[][] px = interpolate(). That gives the new px to pick from
		// at every timestep- and that's it!! check if within the bounds of the drift period.

		double[][] px = px_current;
		double[][] pygx = pygxbd;

		Instance inst = new DenseInstance(streamHeader.numAttributes());
		inst.setDataset(streamHeader);

		int[] indexes = new int[nAttributes.getValue()];

		/* This for-loop contains an algorithm to sample from a multinomial distribution. It is
		 * implemented correctly. See http://www.win.tue.nl/~marko/2WB05/lecture8.pdf
		 * Pick U from the uniform distribution. Find the index where the sum of the independent
		 * probability values exceeds the picked value. The smallest index that exceeds U is the outcome.
		 */
		for (int a = 0; a < indexes.length; a++) {
			double rand = r.nextUniform(0.0, 1.0, true);

			int chosenVal = 0;
			double sumProba = px[a][chosenVal];
			while (rand > sumProba) {
				chosenVal++;
				sumProba += px[a][chosenVal];
			}

			indexes[a] = chosenVal;
			inst.setValue(a, chosenVal);
		}

		int lineNoCPT = getIndex(indexes);

		int chosenClassValue = 0;
		while (pygx[lineNoCPT][chosenClassValue] != 1.0) {
			chosenClassValue++;
		}//finds the class Value in pygx, and sets the instance to it
		inst.setClassValue(chosenClassValue);

		nInstancesGeneratedSoFar++;
		// System.out.println("generated "+inst);
		return new InstanceExample(inst);
	}

	@Override
	protected void prepareForUseImpl(TaskMonitor monitor,
			ObjectRepository repository) {
		// prepare the start and end px's here


		System.out.println("burnIn=" + burnInNInstances.getValue());
		generateHeader();

		int nCombinationsValuesForPX = 1;
		for (int a = 0; a < nAttributes.getValue(); a++) {
			nCombinationsValuesForPX *= nValuesPerAttribute.getValue();
		}

		pxbd = new double[nAttributes.getValue()][nValuesPerAttribute.getValue()];
		pygxbd = new double[nCombinationsValuesForPX][nValuesPerAttribute.getValue()];

		RandomGenerator rg = new JDKRandomGenerator();
		rg.setSeed(seed.getValue());
		r = new RandomDataGenerator(rg);

		// generating distribution before drift

		// p(x)
		generateRandomPx(pxbd, r);

		// p(y|x)
		generateRandomPyGivenX(pygxbd, r);

		// generating distribution after drift

		// generating covariate drift

			pxad = new double[nAttributes.getValue()][nValuesPerAttribute.getValue()];
			double obtainedMagnitude;

			cellDirection = new double[nAttributes.getValue()][nValuesPerAttribute.getValue()];
			// if a cell grows, it is marked with a 1, otherwise 0. Let's have half the cells increasing in probability.
			double increasingProportion = 0.5;

			for(int i = 0; i < nAttributes.getValue(); i++) {
				for(int j = 0; j < nValuesPerAttribute.getValue(); j++){
					cellDirection[i][j] = 1; //increasing cell
					if (r.nextUniform(0.0, 1.0, true) < increasingProportion ){
						cellDirection[i][j] = -1; //decreasing cell
					}
				}
			}
			// we've set our cell movement directions.
			// now we need to move our cells up or down in the nextInstance function until the drift mag is achieved.

			/* We really want a monotonous movement towards the final distribution in the gradual case...
			 * we don't want a random walk (at least not yet... this can be generalised later)!!!
			 *
			 * If we split our set into increasers and decreasers, then pick an increase at random, we are adding
			 * this sort of rule to the drift we generate; some points increase until they hit the target
			 */

			/* from that distribution, pick values at random from the cells
			 * take the corresponding value in your starting distribution
			 * replace it
			 */
			System.out.println("Sampling p(x) for required magnitude...");
			do {
				if (driftMagnitudePrior.getValue() >= 0.2) {
					generateRandomPx(pxad, r);
				} else if (driftMagnitudePrior.getValue() < 0.2) {
					generateRandomPxAfterCloseToBefore(driftMagnitudePrior.getValue(), pxbd, pxad, r);
				}
				//note this workaround so he doesn't explore a large number of random distributions!
				obtainedMagnitude = computeMagnitudePX(nCombinationsValuesForPX, pxbd, pxad);
			} while (Math.abs(obtainedMagnitude - driftMagnitudePrior.getValue()) > precisionDriftMagnitude
					.getValue());

			System.out.println("exact magnitude for p(x)="
					+ computeMagnitudePX(nCombinationsValuesForPX, pxbd, pxad) + "\tasked="
					+ driftMagnitudePrior.getValue());

		//Drift conditional is never set... for now don't change pygx
		//pygxad = pygxbd;

		// System.out.println(Arrays.toString(pxbd));
		// System.out.println(Arrays.toString(pxad));

		nInstancesGeneratedSoFar = 0L;

	}

	protected final int getIndex(int... indexes) {
		int index = indexes[0];
		for (int i = 1; i < indexes.length; i++) {
			index *= nValuesPerAttribute.getValue();
			index += indexes[i];
		}
		return index;

	}

}