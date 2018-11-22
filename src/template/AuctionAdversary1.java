package template;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import logist.LogistSettings;
import logist.agent.Agent;
import logist.behavior.AuctionBehavior;
import logist.plan.Plan;
import logist.simulation.Vehicle;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;

/**
 * The auction agent.
 */
@SuppressWarnings("unused")
public class AuctionAdversary1 implements AuctionBehavior {

	private final static double BID_RATIO = 9./10.;
	private final static double MARGINAL_COST_RATIO = 2./3.;
	private final static long TIMEOUT_BID = logist.LogistPlatform.getSettings().get(logist.LogistSettings.TimeoutKey.BID) - logist.LogistPlatform.getSettings().get(logist.LogistSettings.TimeoutKey.BID) / 10; // 10% margin
	private final static long TIMEOUT_PLAN = logist.LogistPlatform.getSettings().get(logist.LogistSettings.TimeoutKey.PLAN) - logist.LogistPlatform.getSettings().get(logist.LogistSettings.TimeoutKey.PLAN) / 10; // 10% margin
//	private final static long TIMEOUT_BID = 5 * 1000;
//	private final static long TIMEOUT_PLAN = 5 * 1000;
	private final static double FUTURE_TASK_THRESHOLD = 0.20;
	
	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private List<Vehicle> vehicles;
	private List<Task> tasks;
	private Solution currentPlans;
	private Solution futurePlans;
	private HashMap<Integer, ArrayList<Task>> attributions = new HashMap<Integer, ArrayList<Task>>();
	private long estimatedBid; // estimation of others' marginal cost
	private double bias = 0;
	private double difference = 0;
	private double taskBiasCount = 1;
	private int opponentID = -1;
	
	
	@Override
	public void setup(Topology topology, TaskDistribution distribution, Agent agent) {
		
		System.out.println("TIMEOUT_BID: " + TIMEOUT_BID);
		System.out.println("TIMEOUT_PLAN: " + TIMEOUT_PLAN);

		this.topology = topology;
		this.distribution = distribution;
		this.agent = agent;
		this.vehicles = agent.vehicles();
		this.tasks = new ArrayList<Task>();
		
		Solution.vehicles = this.vehicles;
		
		currentPlans = new Solution(new ArrayList<Task>());
		currentPlans.cost = 0.0;
		futurePlans = new Solution(new ArrayList<Task>());
		futurePlans.cost = Double.POSITIVE_INFINITY;

	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		
		if (opponentID < 0) {
			opponentID = (bids.length - 1) ^ agent.id(); // Only if there are only two agents
		}
		
		//System.out.println("***********************************");
		//System.out.println("Task " + previous.id + "   Winner:" + winner + " for " + bids[winner] + "adversary id: " + agent.id());
		
		if (winner == agent.id()) {
			this.tasks.add(previous);
			this.currentPlans = this.futurePlans;
		}
		
		/*System.out.println("estimatedBid: " + estimatedBid);
		System.out.println("estimatedBid (without bias): " + (estimatedBid + Math.round(bias)));
		System.out.println("Actual bid: " + bids[opponentID]);
		System.out.println("*Difference: " + (estimatedBid - bids[opponentID]));*/
		
		difference += (estimatedBid - bids[opponentID]);
		taskBiasCount++;
		bias = difference / ((double) taskBiasCount);
	
		if (attributions.get(winner) == null) {
			ArrayList<Task> tasksList = new ArrayList<Task>();
			tasksList.add(previous);
			attributions.put(winner, tasksList);
		} else {
			attributions.get(winner).add(previous);
		}
		
	}
	
	@Override
	public Long askPrice(Task task) {
		
		long timestart = System.currentTimeMillis();

		ArrayList<Task> futureTasks = new ArrayList<Task>(tasks);
		futureTasks.add(task);
		
		Double minCost = Double.POSITIVE_INFINITY;
		
		while (System.currentTimeMillis() < timestart + TIMEOUT_BID * 1./3.) {
			Solution plan = centralizedPlan(vehicles, futureTasks, 0.8);
			if (plan.cost < minCost) {
				minCost = plan.cost;
				this.futurePlans = plan;
			}
		}
		
		double marginalCost = futurePlans.cost - currentPlans.cost;
		
		/* Estimating the opponent's bid */
		ArrayList<Task> futureAttributions;
		
		// Estimation when the other has no task is not really good, so we don't compute it
		if (attributions.get(opponentID) == null) {
			estimatedBid = 0;
			
		} else {
			
			futureAttributions = new ArrayList<Task>(attributions.get(opponentID));
			futureAttributions.add(task);
			
			Double futureOpponentMinCost = Double.POSITIVE_INFINITY;
			while (System.currentTimeMillis() < timestart + TIMEOUT_BID * 4./6.) {
				Solution plan = centralizedPlan(vehicles, futureAttributions, 0.8);
				futureOpponentMinCost = Math.min(futureOpponentMinCost, plan.cost);
			}
			
			Double currentOpponentMinCost = Double.POSITIVE_INFINITY;
			while (System.currentTimeMillis() < timestart + TIMEOUT_BID) {
				Solution plan = centralizedPlan(vehicles, attributions.get(opponentID), 0.8);
				currentOpponentMinCost = Math.min(currentOpponentMinCost, plan.cost);
			}
			
			estimatedBid = Math.round(futureOpponentMinCost - currentOpponentMinCost);
			
		}
		
		estimatedBid = estimatedBid - Math.round(bias);
		
		//We suppose that the other agent does not bid negatives
		estimatedBid = Math.max(estimatedBid, 0);
		
		if (tasks.size() <= 1) {
			return Math.round(marginalCost * MARGINAL_COST_RATIO);
		}
		
		int futureInterestingTasks = futureInterestingTasks(task.pickupCity, task.deliveryCity);
		
		//System.out.println("Task " + task.id + ": marginalCost = " + marginalCost + ", estimatedBid = " + estimatedBid);

		double percentage = 0.05;
		return Math.round(Math.max(marginalCost - futureInterestingTasks * marginalCost * percentage,
				estimatedBid * BID_RATIO - futureInterestingTasks * estimatedBid * percentage));
		
	}
	
	/**
	 * This function returns the number of interesting tasks on the path
	 * from one start city to one destination city.
	 * An interesting task is a task which pickup and destination city are
	 * on the path and which has an apparition probability greater than some
	 * constant (FUTURE_TASK_THRESHOLD).
	 * @param start the starting city
	 * @param end the destination city
	 * @return the number of future “interesting tasks”
	 */
	private int futureInterestingTasks(City start, City end) {
			
		int interestingFutureTasks = 0;
			
		List<City> path = start.pathTo(end);
		for (int i = 0; i < path.size() - 1; i++) {
			
			City pickup = path.get(i);
			for (int j = i + 1; j < path.size(); j++) {
				City delivery = path.get(j);
				if (distribution.probability(pickup, delivery) > FUTURE_TASK_THRESHOLD) {
					interestingFutureTasks++;
				}
			}
			
		}
				
		return interestingFutureTasks;
		
	}
	
	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {
		
		long timestart = System.currentTimeMillis();
		
		double income = 0;
		for (Task task : tasks) {
			income += task.reward;
		}
		
		Solution sol = centralizedPlan(vehicles, new ArrayList<Task>(tasks), 0.8);
		while (System.currentTimeMillis() < timestart + TIMEOUT_PLAN) {
			Solution plan = centralizedPlan(vehicles, new ArrayList<Task>(tasks), 0.8);
			if (plan.cost < sol.cost) {
				sol = plan;
			}
		}
		
		System.out.println("************** \tAgent: " + (income - sol.cost));
		
		return sol.getPlan();
	}

	private Solution centralizedPlan(List<Vehicle> vehicles, ArrayList<Task> tasks, double p) {

		Solution.vehicles = vehicles;

		Solution Aold = new Solution(tasks);
		Aold.cost = Double.POSITIVE_INFINITY;
		
		// The biggest vehicle handles tasks sequentially
		Solution A = selectInitialSolution(vehicles, tasks);
		
		if (!A.verifyConstraints()) {
			System.err.println("At least one task is too big for the biggest capacity's vehicle!");
			System.exit(-1);
		}
		
		List<Solution> N = null;

		int count = 0;
		
		//We continue while we improve
		while (count < 10000 && A.cost < Aold.cost) {
			Aold = new Solution(A);
			
			N = chooseNeighbours(Aold, tasks, vehicles);
			
			//We also add the old state in order to prevent NullPointerExceptions if no neighbour is better
			N.add(Aold);
			
			//Select the best solution among the neighbours (and the current solution)
			if (Math.random() < p) {
				A = localChoice(N);
			} else {
				A = N.get((int) Math.random() * N.size());
			}
			
			count++;
		}
		
		return A;
	

	}

	/**
	 * As an initial solution, we just take the vehicle with biggest capacity
	 * and assign all the tasks to it, sequentially.
	 * @param vehicles the list of vehicles
	 * @param tasks the liste of tasks
	 * @return an initial solution
	 */
	private Solution selectInitialSolution(List<Vehicle> vehicles, List<Task> tasks) {

		Vehicle biggestVehicle = null;
		int maxCapacity = 0;
		for (Vehicle vehicle : vehicles) {
			if (vehicle.capacity() > maxCapacity) {
				maxCapacity = vehicle.capacity();
				biggestVehicle = vehicle;
			}
		}

		Solution initialSolution = new Solution(tasks);

		for (Task task : tasks) {
			initialSolution.actionsList.get(biggestVehicle).add(new Action(task, "pickup"));
			initialSolution.actionsList.get(biggestVehicle).add(new Action(task, "delivery"));
		}

		initialSolution.computeCost();

		return initialSolution;
		
	}

	private List<Solution> chooseNeighbours(Solution Aold, List<Task> tasks, List<Vehicle> vehicles) {

		List<Solution> N = new ArrayList<Solution>();
		
		for (Vehicle vi : vehicles) {
			
			if (!Aold.actionsList.get(vi).isEmpty()) {
				
				// Applying the changing vehicle operation:
				for (Vehicle vj : vehicles) {
					if (!vj.equals(vi)) {
						List<Solution> As = changingVehicle(Aold, vi, vj);
						N.addAll(As);
					}
				}
				
				// Applying the changing task order operation:
				List<Solution> As = changingTaskOrder(Aold, vi);
				N.addAll(As);
						
			}
			
		}

		return N;

	}

	/*
	 * We choose the best local solution. If multiple solutions are equally good, we choose one at random.
	 */
	private Solution localChoice(List<Solution> N) {

		List<Solution> bestSolutions = new ArrayList<Solution>();
		double leastCost = Double.POSITIVE_INFINITY;

		for (Solution solution : N) {
			
			if (solution.cost < leastCost) {
				leastCost = solution.cost;
				bestSolutions = new ArrayList<Solution>();
				bestSolutions.add(solution);
				
			} else if (solution.cost == leastCost) {
				bestSolutions.add(solution);
			}
		}
	
		return bestSolutions.get((int) (Math.random() * bestSolutions.size()));
		
	}
	
	/* 
	 * We generate all the neighbours by giving one task handled by v1 and giving it to v2
	 */
	
	public List<Solution> changingVehicle(Solution A, Vehicle v1, Vehicle v2) {
		
		List<Solution> solutions = new ArrayList<Solution>();
		
		//We can give any task of v1 to v2
		for(int actionIndex = 0; actionIndex < A.actionsList.get(v1).size(); actionIndex++) {
			
			Solution A1 = new Solution(A);
			
			Action pickupAction = A.actionsList.get(v1).get(actionIndex);
			
			if (pickupAction.actionType.equals("pickup")) { // a pickup action
				
				Action deliveryAction = new Action(pickupAction.task, "delivery");
				
				// We remove the actions from v1
				A1.actionsList.get(v1).remove(pickupAction);
				A1.actionsList.get(v1).remove(deliveryAction);
				
				// And then put them anywhere in the actionsList of v2
				for (int i = 0; i <= A1.actionsList.get(v2).size(); i++) {
					
					// We have a '+1' because once the pickup is inserted, the size is increased.
					for (int j = 0; j <= A1.actionsList.get(v2).size() + 1; j++) {
						
						Solution A_tmp = new Solution(A1);
						A_tmp.actionsList.get(v2).add(i, pickupAction);
						A_tmp.actionsList.get(v2).add(j, deliveryAction);
						A_tmp.computeCost();
						
						
						// We only keep the plan if it satisfies the constraints and is better than the current solution
						if (A_tmp.verifyConstraints() && A_tmp.cost < A.cost) {
							solutions.add(A_tmp);
						}
					
					}
					
				}
				
			}
			
		}
		
		return solutions;
		
	}
	
	
	/*
	 * We exchange the order of two given tasks
	 */
	public List<Solution> changingTaskOrder(Solution A, Vehicle vi) {
		
		List<Solution> solutions = new ArrayList<Solution>();
		
		for (Action a1 : A.actionsList.get(vi)) {
			for (Action a2 : A.actionsList.get(vi)) {
				if (!a1.equals(a2)) {
					
					Solution A_tmp = new Solution(A);
					int indexT1 = A_tmp.actionsList.get(vi).indexOf(a1);
					int indexT2 = A_tmp.actionsList.get(vi).indexOf(a2);
					
					A_tmp.actionsList.get(vi).remove(a1);
					A_tmp.actionsList.get(vi).remove(a2);
					
					// We have to insert the smallest index first, otherwise there are some out-of-bound issues.
					if (indexT1 < indexT2) {
						A_tmp.actionsList.get(vi).add(indexT1, a2);
						A_tmp.actionsList.get(vi).add(indexT2, a1);
					} else {
						A_tmp.actionsList.get(vi).add(indexT2, a1);
						A_tmp.actionsList.get(vi).add(indexT1, a2);
					}
					
					A_tmp.computeCost();
					
					// We only keep the plan if it satisfies the constraints and is better than the current solution
					if (A_tmp.verifyConstraints() && A_tmp.cost < A.cost) {
						solutions.add(A_tmp);
					}
			
				}
			}
		}
		
		return solutions;

	}

}

class Solution {

	protected HashMap<Vehicle, List<Action>> actionsList; // Used to store the actions of each vehicle
	protected Double cost;

	public static List<Vehicle> vehicles;
	public  List<Task> tasks;
	
	public Solution(List<Task> tasks) {
		this.tasks = tasks;
		actionsList = new HashMap<Vehicle, List<Action>>();
		for (Vehicle vehicle : vehicles) {
			actionsList.put(vehicle, new ArrayList<Action>());
		}
	}

	public Solution(Solution parentSolution) {
		actionsList = new HashMap<Vehicle, List<Action>>();
		for (Vehicle vehicle : vehicles) {
			actionsList.put(vehicle, new ArrayList<Action>(parentSolution.actionsList.get(vehicle)));
		}
		computeCost();
		tasks = new ArrayList<Task>(parentSolution.tasks);
	}

	/*
	 * Generate the plan for each vehicle for this solution
	 */
	public List<Plan> getPlan() {

		List<Plan> plans = new ArrayList<Plan>();
		
		for (Vehicle vehicle : vehicles) {
			
			List<Action> actions = actionsList.get(vehicle);
			City current = vehicle.homeCity();
			Plan plan = new Plan(current);
			
			for (Action action: actions) {
				
				for (City city : current.pathTo(action.city)) {
					plan.appendMove(city);
				}
				
				if (action.actionType.equals("pickup")) {
					plan.appendPickup(action.task);
				} else if (action.actionType.equals("delivery")) {
					plan.appendDelivery(action.task);
				} else {
					System.err.println("[Error] getPlan(): some action is neither a pickup nor a delivery action.");
				}
				
				current = action.city;
				
			}
			
			plans.add(plan);
			
			System.out.println("Vehicle " + (vehicle.id() + 1) + "'s cost is " + (plan.totalDistance() * vehicle.costPerKm())+" ("+actions.size()/2+" Tasks: "+ plan+")");
			
		}

		return plans;
	}

	
	void computeCost() {
		double newCost = 0.0;
		
		for (Vehicle vehicle : vehicles) {
			City currentCity = vehicle.homeCity();
			for (Action action : actionsList.get(vehicle)) {
				newCost += currentCity.distanceTo(action.city) * vehicle.costPerKm();
				currentCity = action.city;
			}
		}
		
		this.cost = newCost;
	}

	/**
	 * Verify the constraints.
	 * @return true if the constraints are fulfilled, false otherwise.
	 */
	 Boolean verifyConstraints() {

		/*
		 * Constraint 1
		 * We only accept if the vehicle can carry the tasks, at any moment
		 */		
		for (Vehicle vehicle : vehicles) {
			
			int carriedWeight = 0;
			
			for (Action action: actionsList.get(vehicle)) {
				
				if (action.actionType.equals("pickup")) {
					carriedWeight += action.task.weight;
				} else {
					carriedWeight -= action.task.weight;
				}
	
				if (carriedWeight > vehicle.capacity()) {
					return false;
				}
				
			}
			
		}
		
		
		/*
		 * Constraint 2
		 * Pickups actions of a task must be before corresponding deliveries, all picked up tasks must be delivered and all tasks available must be picked up.
		 */
		ArrayList<Task> availableTasks = new ArrayList<Task>(tasks);
		
		for (Vehicle vehicle : vehicles) {
			
			ArrayList<Task> stack = new ArrayList<Task>();
			
			for (Object obj : actionsList.get(vehicle)) {
				
				Action action = (Action) obj;
				
				if (action.actionType.equals("pickup")) {
					stack.add(action.task);
					availableTasks.remove(action.task);
				} else if (action.actionType.equals("delivery")) {
					if (!stack.remove(action.task)) return false;
				} else {
					System.err.println("[Error] verifyConstraints(): some action is neither a pickup nor a delivery action.");
				}
				
			}
			
			// All picked up tasks must be delivered
			if (!stack.isEmpty()) return false;
			
		}
		
		// Verify that there is no task left
		if (!availableTasks.isEmpty()) {
			return false;
		}

		return true;
	}
	 
	@Override
	public String toString() {
		
		String string = "Tasks: "+tasks+"\n";
		string += "Cost: "+cost+"\n";
		string += "Vehicles' actions:";
		for (Vehicle vehicle : vehicles){
			string = string + "\n" + actionsList.get(vehicle);
		}
		
		return string;
	}
	 
}

class Action {
	
	protected Task task;
	protected String actionType;
	protected City city;
	
	public Action(Task task, String type) {
		this.task = task;
		actionType = type;
		if (actionType.equals("pickup")) {
			city = this.task.pickupCity;
		} else if (actionType.equals("delivery")) {
			city = this.task.deliveryCity;
		} else {
			System.err.println("[Error] Attempt to create an action that is not a pickup nor a delivery action.");
		}
	}
	
	@Override
	public String toString() {
		return actionType + " Task" + task.id + " in " + city;
	}
	
	public boolean equals(Object obj) {
		Action action = (Action) obj;
		return this.task.equals(action.task) && this.actionType.equals(action.actionType);
	}
}