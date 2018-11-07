package template;

//the list of imports
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;

import logist.Measures;
import logist.behavior.AuctionBehavior;
import logist.agent.Agent;
import logist.simulation.Vehicle;
import logist.plan.Plan;
import logist.task.Task;
import logist.task.TaskDistribution;
import logist.task.TaskSet;
import logist.topology.Topology;
import logist.topology.Topology.City;

/**
 * A very simple auction agent that assigns all tasks to its first vehicle and
 * handles them sequentially.
 * 
 */
@SuppressWarnings("unused")
public class AuctionTemplate implements AuctionBehavior {

	private Topology topology;
	private TaskDistribution distribution;
	private Agent agent;
	private Random random;
	private Vehicle vehicle;
	private City currentCity;
	List<Act> listAct = new ArrayList<Act>();

    double cost;
    double nextcost;
    double bestcost;
    List<Plan> bestPlans = new ArrayList<Plan>(); // bestplan for this path
    List<Plan> ultraPlans = new ArrayList<Plan>(); // Bestplan overall
    
    Hashtable<Integer, List<Act>> nextTask = new Hashtable<Integer, List<Act>>();
    Hashtable<Integer, List<Act>> nextTask_clone = new Hashtable<Integer, List<Act>>();
	
    
    // CAN CHANGE THE NUMBERS
    long NUMBER_OF_SEARCH_STEP = 500000; // number of computed plan
    long number_iter = 0; 
    long number_iter_max = 10000; // number of iteration with a same plan before restart 
	@Override
	public void setup(Topology topology, TaskDistribution distribution,
			Agent agent) {

		this.topology = topology;
		this.distribution = distribution;
		this.agent = agent;
		this.vehicle = agent.vehicles().get(0);
		this.currentCity = vehicle.homeCity();

		long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		this.random = new Random(seed);
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		if (winner == agent.id()) {
			currentCity = previous.deliveryCity;
		}
	}
	
	@Override
	public Long askPrice(Task task) {

		if (vehicle.capacity() < task.weight)
			return null;

		long distanceTask = task.pickupCity.distanceUnitsTo(task.deliveryCity);
		long distanceSum = distanceTask
				+ currentCity.distanceUnitsTo(task.pickupCity);
		double marginalCost = Measures.unitsToKM(distanceSum
				* vehicle.costPerKm());

		double ratio = 1.0 + (random.nextDouble() * 0.05 * task.id);
		double bid = ratio * marginalCost;

		return (long) Math.round(bid);
	}

	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {
		
		long time_start = System.currentTimeMillis();
        
        //INITIALIZATION
        cost = 200000;
        bestcost = 200000;
        while (bestPlans.size() < vehicles.size()) {
        	bestPlans.add(Plan.EMPTY);
        }
        while (ultraPlans.size() < vehicles.size()) {
        	ultraPlans.add(Plan.EMPTY);
        }       
       
        //Creat a List of task to work with indexes
        List<Task> taskList = new ArrayList<Task>();
        for (Task task : tasks) {
        	taskList.add(task)	;
        }     
        
        // generate all possible action that have to be performed ( 2 for each task, pick up and deliver )  
        
        //boolean = False => pickup
        //boolean = False => deliver
        //ListAct = [pickup_task0, deliver_task0,pickup_task1,deliver_task1......]
        for(int i = 0 ; i < taskList.size() ; i++)
        {	Task task = taskList.get(i);
        	listAct.add(new Act(task, false));
        	listAct.add(new Act(task, true));
        }
        
        //initilize HASHTABLE
        // hashtable with list of task corresponding to vehicle id
        for(int j = 0; j < vehicles.size() ; j++) {
        	List<Act> act = new ArrayList<Act>();
           
        	nextTask.put(j, act);
        }
        
        // INITIAL SOLUTION
        // We give to all vehicle the same amount of task
        //Vehicle 1 : pickup_task0, deliver_task0 , pickup_taskN+1 ,deliver_taskN+1
        //Vehicle 2 : pickup_task1,deliver_task1 , ... , ...
        //Vehicle 3 : pickup_task2,deliver_task2
        //...
        //Vehicle N : pickup_taskN,deliver_taskN
        
        int n = 0; 
    	while(n < listAct.size() ) {
    		for(int j = 0 ; j < vehicles.size() ; j++) {
    			List<Act> act = nextTask.get(j);
    			act.add(listAct.get(n));
        		act.add(listAct.get(n+1));
        		nextTask.put(j, act);
        		

        		n =  n + 2;
        		if(n == listAct.size() ) {break;}
        		        		
    		}
    	}
    	
        //INITIALIZATION of nextTask_clone
    	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
		List<Plan> plans = new ArrayList<Plan>();
		for(int k = 0 ; k< NUMBER_OF_SEARCH_STEP ; k++) {
				
			plans.clear();	
			
			for(int i = 0 ; i< vehicles.size(); i++)
	        {
				Plan planVehicle= hashToPlan(vehicles.get(i), nextTask.get(i));
				plans.add(planVehicle);
				
	        }		
	        
	        // Plans' cost for all vehicles
	        while (plans.size() < vehicles.size()) {
	            plans.add(Plan.EMPTY);
	        }
	        
	        nextcost = 0;
	        
	        for(int i = 0 ; i< plans.size(); i++)
	        {
	        	nextcost = nextcost + plans.get(i).totalDistance();
	        	
	        }
	        
	        //OPIMIZATION = finding a better neigbour , if can't find after a number of iteration restart from initial solution
	        nextTask = optimize( vehicles, taskList,plans);
	        
	        long time_end = System.currentTimeMillis();
	        long duration = time_end - time_start;
	        //System.out.println("The plan was generated in "+duration+" milliseconds. COST BEST_PLAN = "+bestcost +" COST Actual Plan = "+cost + "   Cost neighbour = "+ nextcost );
			
		}
		List<Plan> re;
		//System.out.println("Ultra PLANS: "+ ultraPlans.toString() );
		//System.out.println("Best PLANS: "+ bestPlans.toString() );

		//return the bestPLAN with the shortest distance
		if(bestcost  > cost ) {re= ultraPlans;
		bestcost = cost;}
		else {re= bestPlans;}
		
		System.out.println("FINAL PLAN COST: "+ bestcost );
		//System.out.println("FINAL PLANS: "+ re.toString() );
		
        return bestPlans;
    }

    //For a vehicle's solution in the hashtable, return a plan
    private Plan hashToPlan(Vehicle vehicle,  List<Act> actions) {
        City current = vehicle.getCurrentCity();
        Plan plan = new Plan(current);
        
        
        
        for(int i =0 ; i < actions.size() ; i++)
        {
        	Act action =   actions.get(i);
        	if(action.get_deliver() == false) { // if action is to pickup task add city and pick up
        		
        		for (City city : current.pathTo(action.get_task().pickupCity)) { 
                    plan.appendMove(city);
                }

                plan.appendPickup(action.get_task());
                current = action.get_task().pickupCity;
        	}
        	else { // if action is to deliver task , add city and deliver task
        		
        		for (City city : current.pathTo(action.get_task().deliveryCity)) {
                    plan.appendMove(city);
                }
        		
        		plan.appendDelivery(action.get_task());
        		current = action.get_task().deliveryCity;
        	}
        	 
        }
        
        return plan;
    }
        
        
      
    
    
    
    // transformation on the hashtable, take one task (2 actions) from a vehicle v1 and put it at the end of vehicle v2
    public void give( Task task,int v1, int v2) {
    	List<Act> actions1 = nextTask_clone.get(v1);
    	List<Act> actions2 = nextTask_clone.get(v2);
    	int id1 = 0;
    	int id2 = 0;
    	
    	
    	
    	for(int i = 0; i < actions1.size(); i++)
    	{ 	//find actions according to task
    		if(actions1.get(i).get_task().id == task.id && !actions1.get(i).get_deliver()) {
    			id1 = i;
    			
    	    	
    		}
    		if(actions1.get(i).get_task().id == task.id && actions1.get(i).get_deliver()) {
    			id2 = i;
    			
    		}
    	}
    	
    	
    	
    	Act act1 = actions1.get(id1);
    	Act act2 = actions1.get(id2);
    	
    	actions2.add(act2);
		actions1.remove(act2);
    	actions2.add(act1);
		actions1.remove(act1);
   
    	
    	
    	
    	nextTask_clone.put(v1, actions1);
    	nextTask_clone.put(v2, actions2);
    	
    	

	}
    
    // Swap the position of 2 actions in the vehicle's list of action
    public void swap( Act action1,Act action2,int v ) {
    	List<Act> actions1 = nextTask_clone.get(v);
    	
    	int id1 = 0;
    	int id2 = 0;
    	for(int i = 0; i < actions1.size(); i++)
    	{ 	
    		if(actions1.get(i) == action1) {id1 = i;}
    		if(actions1.get(i) == action2) {id2 = i;}
    	}
    	Act inter = actions1.get(id1);
    	actions1.set(id1, actions1.get(id2));
    	actions1.set(id2, inter);

    	nextTask_clone.put(v, actions1);

	}
    
    
    //constraints
    private boolean constraint(List<Vehicle> vehicles, Hashtable<Integer, List<Act>> nextTask_clone )
    {
    	
    	boolean validation = true; // retrun false if one the constraint is not respected
    	for(int v =0; v < vehicles.size() ; v++) // for all vehicle
    	{
    		List<Act> actions = nextTask_clone.get(v);
    		int weight = 0;
    		for(int i = 0; i< actions.size() ; i++ ) { // for all possible actions
    			Act action1 = actions.get(i);
    			
    			for(int j = 0; j< actions.size() ; j++ ) {// for all possible actions
    				Act action2 = actions.get(j);
    				if( action1.get_task().id == action2.get_task().id )  { //if same task
    					if( i != j ) 
    					{// diff action
	    					
	    					// check if action pick up is before action deliver
	    					if(action1.get_deliver()  ) 
	    					{ // if action1 is deliver
	    						if(i<j) {swap(action1,action2,v);
	    								}	//if action1 comes after deliver
	    					}
	    					else
	    					{ // if action1 is pickup
	    						if(i>j) {swap(action1,action2,v);
	    								}// if action1 comes before pick up 
	    						
	    					}
	    					
	    				}
					}
    			}
    			
    			
    			
    		// make sure the vehicle is not overloaded during the sequence of action	
    		if(action1.get_deliver()) {weight = weight - action1.get_task().weight;} // deliver, substract weight
    		else {weight = weight + action1.get_task().weight;} // pick up , add weight
    		if(vehicles.get(v).capacity() < weight) // overcapacity
    		{
    			validation = false;
    			//System.out.println("Vehicle: " + v +" is OVERLOADED");
    		}
    		
    		}
    		
    		
    	}
    	
    		
    		
    		
    		
    
    	return validation;
    }
    
    
    
    
    private Hashtable<Integer, List<Act>>  optimize(List<Vehicle> vehicles, List<Task> taskList , List<Plan> plans ){
    	
		//Do a random transformation
		//If it is a solution , 
    	//compute plan,
    	//get cost
    	//if cost is lower keep the plan.
    	
    	
    	
    	// found better plan
        if (nextcost < cost ) {
        	
        	// if cost is better then we update the nextTask
	    	
	    	nextTask = (Hashtable<Integer, List<Act>>) nextTask_clone.clone(); // make nextTask the new checkpoint 
	    	cost = nextcost;
	    	Collections.copy(bestPlans, plans);
	    	number_iter = 0; // restart count because found better plan
        }
        else { 
        	
        	
        	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone(); // load the last checkpoint
        	
        	number_iter = number_iter + 1; //count number of time the plan don't change
        	
        	
        }
        
        if(number_iter >= number_iter_max ) {
        	if(bestcost > cost) {
        		Collections.copy(ultraPlans, bestPlans); //best plan for this init state is stored 
        		bestcost = cost;//best bestscore for this init solution is stored 
        	}
        	//restart from a new initial solution
        	//shuffle(vehicles); //Don't work
        	restart(vehicles); //useless
        	
        	number_iter = 0;
        	
        }
        
        
        
        // pick a random transformation
        Random rand = new Random();
      //here do transformation Next_clone = transf(next)
    	do {
    		if(rand.nextBoolean()) { //give action
    			
    			int v1 = rand.nextInt(vehicles.size());
    			int v2 = rand.nextInt(vehicles.size());
    			while(v1 == v2 || nextTask_clone.get(v1).size() == 2 ) {v1 = rand.nextInt(vehicles.size());
    							v2 = rand.nextInt(vehicles.size());}// assure vehicle different
    			
    			int t = rand.nextInt(nextTask_clone.get(v1).size()); // pick random action in choosen vehicle
    			Task task = nextTask_clone.get(v1).get(t).get_task(); // get task
    			give(task,v1,v2);
    		}
			else {	//swap action
				
				int v1 = rand.nextInt(vehicles.size());
				int a1 = 0;
				int a2 = 0;
				
    			while(a1 == a2) {   a1 = rand.nextInt(nextTask_clone.get(v1).size());
								    a2 = rand.nextInt(nextTask_clone.get(v1).size());}  // assure action in the v is diff
    			
    			Act action1 = nextTask_clone.get(v1).get(a1);
    			Act action2 = nextTask_clone.get(v1).get(a2);
    			swap(action1,action2,v1);
    			
    			
    			
    		}
    		
    		
    		//System.out.println("NOPE !");
    		}
    	while(constraint(vehicles,nextTask_clone) == false);
    		
    	
	//System.out.println("Solution !");    
        		
    return nextTask_clone;
		
    }
    
    // restart to the initial solution 
private void restart(List<Vehicle> vehicles) {
	int n = 0; 
	cost = 200000;
	nextTask.clear();
	for(int j = 0; j < vehicles.size() ; j++) {
    	List<Act> act = new ArrayList<Act>();
       
    	nextTask.put(j, act);
    }
	while(n < listAct.size() ) {
		for(int j = 0 ; j < vehicles.size() ; j++) {
			List<Act> act = nextTask.get(j);
			act.add(listAct.get(n));
    		act.add(listAct.get(n+1));
    		nextTask.put(j, act);
    		

    		n =  n + 2;
    		if(n == listAct.size() ) {break;}
    		        		
		}
		
		
		
	}
	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
}    

// not used ( restart from a random solution )
private void shuffle(List<Vehicle> vehicles) {
	//System.out.println("RESHUFFLE");
	Random rand = new Random();
	int n = 0; 
	cost = 50000;
	int j = 0;
	List<Act> act = nextTask.get(j);
	while(n < listAct.size())  {
		while(vehicles.get(j).capacity() < get_weight(vehicles, j, act) )  {
			//System.out.println("cap : " +vehicles.get(j).capacity() +"Weight : "+ get_weight(vehicles, j, act));
			j = rand.nextInt(vehicles.size());
			act = nextTask.get(j);
			
			
		}
			
			
		
		act.add(listAct.get(n));
 		act.add(listAct.get((n+1)));
 		nextTask.put(j, act);
 		n =  n + 2;
 		if(n == (listAct.size() -1 )) {break;}
	 		        		
		
 	}
	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
}


    
    





private int get_weight(List<Vehicle>vehicles, int v, List<Act> actions) {
	int weight = 0;
	for(int i =0 ; i<actions.size() ;i++) {
		
		Act action = actions.get(i);
		if(action.get_deliver()) {weight = weight - action.get_task().weight;} // deliver
		else {weight = weight + action.get_task().weight;} // pick up
	
	
	
	}
return weight;
}

	private Plan naivePlan(Vehicle vehicle, TaskSet tasks) {
		City current = vehicle.getCurrentCity();
		Plan plan = new Plan(current);

		for (Task task : tasks) {
			// move: current city => pickup location
			for (City city : current.pathTo(task.pickupCity))
				plan.appendMove(city);

			plan.appendPickup(task);

			// move: pickup location => delivery location
			for (City city : task.path())
				plan.appendMove(city);

			plan.appendDelivery(task);

			// set current city
			current = task.deliveryCity;
		}
		return plan;
	}
}

//class of action
class Act{
	private Task task;
	private boolean deliver;
	
	public Act(Task task, boolean deliver) 
	{
		
		this.deliver = deliver;
		this.task = task;
		
	}
	
	public boolean get_deliver() {
		return this.deliver;
	}
	public Task get_task() {
		return this.task;
	}
	
	
	
	 
}
