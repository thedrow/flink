package eu.stratosphere.sopremo.sdaa11.clustering.initial;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import eu.stratosphere.sopremo.ElementaryOperator;
import eu.stratosphere.sopremo.expressions.EvaluationExpression;
import eu.stratosphere.sopremo.expressions.ObjectAccess;
import eu.stratosphere.sopremo.pact.JsonCollector;
import eu.stratosphere.sopremo.pact.SopremoReduce;
import eu.stratosphere.sopremo.sdaa11.Annotator;
import eu.stratosphere.sopremo.sdaa11.clustering.Point;
import eu.stratosphere.sopremo.sdaa11.clustering.initial.ClusterQueue.ClusterPair;
import eu.stratosphere.sopremo.type.ArrayNode;
import eu.stratosphere.sopremo.type.IArrayNode;
import eu.stratosphere.sopremo.type.IJsonNode;
import eu.stratosphere.sopremo.type.ObjectNode;
import eu.stratosphere.sopremo.type.TextNode;

public class SequentialClustering extends
		ElementaryOperator<SequentialClustering> {

	private static final long serialVersionUID = 5563265035325926095L;
	
	public static final String SCHEMA_ID = "id";

	public static final String SCHEMA_POINTS = "points";

	public static final String SCHEMA_CLUSTROID = "clustroid";

	

	/** The maximum radius of a cluster. */
	private int maxRadius;

	/** The maximum number of points of a cluster. */
	private int maxSize;

	public int getMaxRadius() {
		return this.maxRadius;
	}

	public void setMaxRadius(final int maxRadius) {
		this.maxRadius = maxRadius;
	}

	public int getMaxSize() {
		return this.maxSize;
	}

	public void setMaxSize(final int maxSize) {
		this.maxSize = maxSize;
	}

	@Override
	public Iterable<? extends EvaluationExpression> getKeyExpressions() {
		return Arrays.asList(new ObjectAccess(Annotator.DUMMY_KEY));
	}

	public static class Implementation extends SopremoReduce {

		private int maxRadius;
		private int maxSize;

		private ClusterQueue queue = new ClusterQueue();
		private final List<HierarchicalCluster> clusters = new ArrayList<HierarchicalCluster>();
		private int idCounter = 0;

		@Override
		protected void reduce(final IArrayNode values, final JsonCollector out) {
			this.addPoints(values);
			this.cluster();
			this.emitClusters(out);
		}

		private void addPoints(final IArrayNode values) {
			for (final IJsonNode value : values) {
				final Point point = new Point();
				point.read(Annotator.deannotate(value));
				this.queue.add(new BaseCluster(point, String.valueOf(this
						.createNewId())));
			}
		}

		private void cluster() {
			// Hierarchical clustering: Cluster until there is only one cluster
			// left.
			while (this.queue.getNumberOfClusters() > 1) {
				final ClusterPair pair = this.queue.getFirstElement();
				final HierarchicalCluster cluster1 = pair.getCluster1();
				final HierarchicalCluster cluster2 = pair.getCluster2();

				final HierarchicalCluster mergedCluster = new MergedCluster(
						cluster1, cluster2, this.createNewId());
				this.queue.removeCluster(cluster1);
				this.queue.removeCluster(cluster2);

				// If the new cluster can be a final cluster, we will not
				// consider its children anymore.
				final boolean makeFinal = this.canBeFinal(mergedCluster);
				mergedCluster.makeFinal(makeFinal);
				if (makeFinal)
					this.queue.add(mergedCluster);
				else
					for (final HierarchicalCluster child : mergedCluster
							.getChildren())
						this.clusters.add(child);
			}
			this.clusters.addAll(this.queue.getClusters());
			this.queue = null;
		}

		/**
		 * Tells whether the cluster can be used as cluster in GRGPF.<br>
		 * This method is to satisfy the following condition:<br>
		 * <i>!canBeFinal(c1) | !canBeFinal(c2) => !canBeFinal(c1+c2)</i>
		 */
		private boolean canBeFinal(final HierarchicalCluster cluster) {
			return cluster.canBeFinal() && cluster.getRadius() < this.maxRadius
					&& cluster.size() < this.maxSize;
		}

		private void emitClusters(final JsonCollector out) {
			for (final HierarchicalCluster cluster : this.clusters)
				this.emit(cluster, out);
		}

		private void emit(final HierarchicalCluster cluster,
				final JsonCollector out) {
			if (cluster.isFinal()) {
				final ArrayNode pointsNode = new ArrayNode();
				for (final Point point : cluster.getPoints())
					pointsNode.add(point.write((IJsonNode) null));

				final ObjectNode clusterNode = new ObjectNode();
				clusterNode.put(SCHEMA_ID, new TextNode(cluster.getId()));
				clusterNode.put(SCHEMA_CLUSTROID,
						cluster.getClustroid().write((IJsonNode) null));
				clusterNode.put(SCHEMA_POINTS, pointsNode);

				out.collect(clusterNode);
			} else
				for (final HierarchicalCluster child : cluster.getChildren())
					this.emit(child, out);
		}

		private String createNewId() {
			return String.valueOf(this.idCounter++);
		}
	}

}
