package com.pku.ebolt.engine;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.TreeMap;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.japi.Creator;

class RouteTree {
	private class RouteTreeNode {
		int upperBound;
		ActorRef target;
		RouteTreeNode (int upperBound, ActorRef target) {
			this.upperBound = upperBound;
			this.target = target;
		}
	}
	
	TreeMap<Integer, RouteTreeNode> routeMap;
	
	RouteTree(ActorRef target) {
		routeMap = new TreeMap<Integer, RouteTreeNode>();
		routeMap.put(0, new RouteTreeNode(Integer.MAX_VALUE, target));
	}
	
	RouteTree(List<ActorRef> targets) {
		Queue<Integer> lowerBounds = new LinkedList<Integer>();
		Iterator<ActorRef> targetIter = targets.iterator();
		assert(targetIter.hasNext());
		routeMap.put(0, new RouteTreeNode(Integer.MAX_VALUE, targetIter.next()));
		lowerBounds.offer(0);
		while (targetIter.hasNext()) {
			int lowerBound = lowerBounds.poll();
			assert(canBeSplit(lowerBound));
			lowerBounds.offer(lowerBound);
			lowerBounds.offer(split(lowerBound, targetIter.next()));
		}
	}
	
	ActorRef route(TupleWrapper tupleWrapper) {
		int key = tupleWrapper.hashCode();
		assert(key >= 0);
		
		Entry<Integer, RouteTreeNode> targetTableItem = routeMap.floorEntry(key);
		return targetTableItem.getValue().target;
	}
	
	ActorRef getTarget(int lowerBound) {
		assert(isValidLowerBound(lowerBound));
		
		return routeMap.get(lowerBound).target;
	}
	
	// Set new target and return old target
	ActorRef setTarget(int lowerBound, ActorRef newTarget) {
		assert(isValidLowerBound(lowerBound));
		RouteTreeNode routeTreeNode = routeMap.get(lowerBound);
		ActorRef oldTarget = routeTreeNode.target;
		routeTreeNode.target = newTarget;
		return oldTarget;
	}
	
	boolean isValidLowerBound(int lowerBound) {
		return routeMap.containsKey(lowerBound);
	}
	
	// If lowerBound == upperBound, the range can not be split
	boolean canBeSplit(int lowerBound) {
		assert(isValidLowerBound(lowerBound));
		
		return lowerBound < routeMap.get(lowerBound).upperBound;
	}
	
	// Set target in new sub range, return new lowerBound
	int split(int lowerBound, ActorRef target) {
		assert(isValidLowerBound(lowerBound));
		assert(target != null);
		
		RouteTreeNode oldRouteTreeNode = routeMap.get(lowerBound);
		int upperBound = oldRouteTreeNode.upperBound;
		
		assert(lowerBound < upperBound); // unit should never be split
		
		int middle = (lowerBound + upperBound) / 2;
		RouteTreeNode newRouteTreeNode = new RouteTreeNode(upperBound, target);
		oldRouteTreeNode.upperBound = middle;
		routeMap.put(middle + 1, newRouteTreeNode);
		return middle + 1;
	}
	
	// Is sibling exists (It may be split or [MIN_VALUE, 0))
	boolean isSiblingAvailable(int lowerBound) {
		assert(isValidLowerBound(lowerBound));

		int siblingLowerBound = siblingNode(lowerBound);
		return (siblingLowerBound >= 0 && !isSiblingSplit(siblingLowerBound, lowerBound));
	}
	
	// Remove target in given range. Return new lowerBound after merging, if you want to update
	// lowerBound of the old target, we can just send a message to it.
	// If failed, return -1
	int merge(int lowerBound) {
		int siblingLowerBound = siblingNode(lowerBound);
		if (isSiblingSplit(siblingLowerBound, lowerBound)) {
			System.err.println("Sibling is split, can not be merged");
			return -1;
		}
		if (siblingLowerBound > lowerBound) {
			routeMap.put(lowerBound, routeMap.get(siblingLowerBound));
			routeMap.remove(siblingLowerBound);
			return lowerBound;
		} else {
			routeMap.get(siblingLowerBound).upperBound = routeMap.get(lowerBound).upperBound;
			routeMap.remove(lowerBound);
			return siblingLowerBound;
		}
	}
	
	// Return sibling target, if sibling is split return null
	ActorRef sibling(int lowerBound) {
		int siblingLowerBound = siblingNode(lowerBound);
		// If siblingLowerBound == MIN_VALUE, it means that lowerBound is 0-MAX_VALUE,
		// it has no siblings.
		if (siblingLowerBound == Integer.MIN_VALUE || isSiblingSplit(siblingLowerBound, lowerBound)) {
			System.err.println("Sibling is split or not exist");
			return null;
		}
		return routeMap.get(siblingLowerBound).target;
	}
	
	// Return whether sibling node is split
	private boolean isSiblingSplit(int siblingLowerBound, int lowerBound) {
		int siblingUpperBound = routeMap.get(siblingLowerBound).upperBound;
		if (siblingLowerBound > lowerBound)
			return (siblingUpperBound - siblingLowerBound) != (siblingLowerBound - 1 - lowerBound);
		else
			return siblingUpperBound != lowerBound - 1;
	}
	
	// Return sibling lower bound, if lowerBound == 0 and upperBound == MAX_VALUE
	// the function will return MIN_VALUE
	// It is sure that:
	// 		siblingNode(lowerBound) != lowerBound
	private int siblingNode(int lowerBound) {
		RouteTreeNode routeTreeNode = routeMap.get(lowerBound);
		int upperBound = routeTreeNode.upperBound;
		
		int x = lowerBound ^ (upperBound + 1); // x
		if ((x & (x-1)) == 0) // Left Node
			// if (upperBound == Integer.MAX_VALUE)
			// upperBound + 1 == Integer.MIN_VALUE
			return upperBound + 1;
		else // Right Node
			return (lowerBound - 1) & lowerBound;
	}
}

class OutputRouter extends UntypedActor {
	RouteTree routerTable;
	
	public static Props props(final List<ActorRef> targetRouters) {
		return Props.create(new Creator<OutputRouter>() {
			private static final long serialVersionUID = 1L;
			public OutputRouter create() throws Exception {
				return new OutputRouter(targetRouters);
			}
		});
	}
	
	OutputRouter(List<ActorRef> targetRouters) {
		routerTable = new RouteTree(targetRouters);
	}
	
	@Override
	public void onReceive(Object msg) throws Exception {
		if (msg instanceof TupleWrapper) {
			TupleWrapper tupleWrapper = (TupleWrapper)msg;
			ActorRef target = routerTable.route(tupleWrapper);
			target.forward(msg, getContext());
		} else unhandled(msg);
	}
}
