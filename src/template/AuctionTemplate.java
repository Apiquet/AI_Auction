package template;

//the list of imports
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;

import logist.LogistSettings;
import logist.Measures;
import logist.behavior.AuctionBehavior;
import logist.config.Parsers;
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
	private long timeout_setup;
    private long timeout_plan;
    private long timeout_auction;
	List<Act> listAct = new ArrayList<Act>();
    
	
    // variables for the centralized algorithm:
    double cost;
    double nextcost;
    double bestcost;
    long number_iter = 0; 
    long number_iter_max = 5000; // number of iteration with a same plan before restart 
    //FastPlan
    int numb_plan_computed = 2000;
    List<Plan> bestPlans = new ArrayList<Plan>(); // bestplan for this path
    List<Plan> ultraPlans = new ArrayList<Plan>(); // Bestplan overall
    
    Hashtable<Integer, List<Act>> nextTask = new Hashtable<Integer, List<Act>>();
    Hashtable<Integer, List<Act>> nextTask_clone = new Hashtable<Integer, List<Act>>();
    
    
    // variables for auction strategy:
    int bids_count = 0;
    
    boolean enemy_influenced = false; //boolean to store the information if we detect that the enemy got influenced
    Long avg_bids_enemy = (long) 0; //variable to verify how the enemy react

    
    double averageProfit = 600; //coeff to set the profit that we will adjust in function of the enemy
    int coeff_bid = 7;

    
    List<Task> task_list_agent = new ArrayList<Task>(); //our and their tasks list
    List<Task> task_list_enemy = new ArrayList<Task>();

    List<Result> result_list_enemy = new ArrayList<Result>(); //all the bids and results of the enemy

    List<Vehicle> vehicles_list; 
    
    
	@Override
	public void setup(Topology topology, TaskDistribution distribution,
			Agent agent) {

		this.topology = topology;
		String city_name = topology.cities().get(0).name;
		
		//verifying the topology to adapt our bids strategy
		//it's based on the average distance between the cities.
		if(city_name.equals("London")) {
			System.out.println("London");
			coeff_bid = 7;
			averageProfit = 750;
		}
		else if(city_name.equals("Brest")) {
			System.out.println("Brest");
			coeff_bid = 26;
			averageProfit= 2400;
		}
		else if(city_name.equals("Lausanne")) {
			System.out.println("Lausanne");
			coeff_bid = 10;
			averageProfit = 600;
		}
		else if(city_name.equals("Amsterdam")) {
			System.out.println("Amsterdam");
			coeff_bid = 7;
			averageProfit = 500;
		}else {
			System.out.println("else: " + topology.cities().get(0).name);
		}
		
		//set up given
		this.distribution = distribution;
		this.agent = agent;
		vehicles_list = agent.vehicles();
		this.vehicle = agent.vehicles().get(0);
		this.currentCity = vehicle.homeCity();
		long seed = -9019554669489983951L * currentCity.hashCode() * agent.id();
		this.random = new Random(seed);
		 LogistSettings ls = null;
	        try {
	            ls = Parsers.parseSettings("config\\settings_auction.xml");
	        }
	        catch (Exception exc) {
	            System.out.println("There was a problem loading the configuration file.");
	        }
		
		// the setup method cannot last more than timeout_setup milliseconds
        timeout_setup = ls.get(LogistSettings.TimeoutKey.SETUP);
        // the plan method cannot execute more than timeout_plan milliseconds
        timeout_plan = ls.get(LogistSettings.TimeoutKey.PLAN);
        
        timeout_auction = ls.get(LogistSettings.TimeoutKey.BID);
        
	}

	@Override
	public void auctionResult(Task previous, int winner, Long[] bids) {
		
		
		if (winner == agent.id()) {
			//System.out.println("we win with bid: " + bids[agent.id()]);
			//updating our table:
			// 1- list of all the tasks won
			Result result = new Result(previous,winner,bids[winner]);
			task_list_enemy.remove(task_list_enemy.size()-1);
			currentCity = previous.deliveryCity;
		}
		else {
			//System.out.println("they win with bid: " + bids[winner]);
			//updating enemy's tables:
			// 1- list of all the bids
			// 2- list of all the tasks won
			Result result = new Result(previous,winner,bids[winner]);
			result_list_enemy.add(result);
			task_list_agent.remove(task_list_agent.size()-1);
		}
		
	}
	
	@Override
	public Long askPrice(Task task) {
		long time_start = System.currentTimeMillis();
		
		//verify that a task is not too heavy for our vehicles
		//return null if no vehicle has the capacity to take the task
		boolean can_a_vehicle_take_task = false;
		for(int i = 0 ; i < vehicles_list.size(); i++) {			
			if(task.weight < vehicles_list.get(i).capacity()) 
			{	
				can_a_vehicle_take_task=true;
				break;
			}
		}
		if(!can_a_vehicle_take_task) {
			task_list_agent.add(task);
			task_list_enemy.add(task);
			return null;
		}
		
		//calculating our plan and the one of the enemy without the new task to then determine the cost of adding 
		//the new task in our task list
	    long cost_agent_previous,cost_enemy_previous;
		if(task_list_agent.size() >= 1){
			cost_agent_previous = fast_plan(vehicles_list, task_list_agent);
		} 
		else {cost_agent_previous  = 0;}
		if(task_list_enemy.size() >= 1) 
		{cost_enemy_previous = fast_plan(vehicles_list, task_list_enemy);
		}
		else {cost_enemy_previous = 0;}
		
		//adding the new task to our tasks list and the enemy's tasks list (will be removed from the list of the looser in auctionResult)
		task_list_agent.add(task);
		task_list_enemy.add(task);
		
        long time_end = System.currentTimeMillis();
        long duration = time_end - time_start;
        
        
        //calculating cost of the new plan (with the new task) for us and the enemy (with our algorithm but it give us an idea)
		long cost_agent,cost_enemy,new_cost_agent,new_cost_enemy;
		cost_agent = fast_plan(vehicles_list, task_list_agent);	
		cost_enemy = fast_plan(vehicles_list, task_list_enemy);	
		
		//looking for an optimal plan, considering the time we have
		while(duration < timeout_auction*0.5) {
			new_cost_agent = fast_plan(vehicles_list, task_list_agent);	
			new_cost_enemy = fast_plan(vehicles_list, task_list_enemy);
			if(new_cost_agent<cost_agent) {cost_agent = new_cost_agent;}
			if(new_cost_enemy<cost_enemy) {cost_enemy = new_cost_enemy;}
			time_end = System.currentTimeMillis();
	        duration = time_end - time_start;
		}
		
		//return large bids for the firsts tasks to determine if the enemy got influenced
		bids_count ++;
		if(bids_count == 2) {
			return (long) 1000*coeff_bid;
		}
		else if(bids_count == 3) {
			return (long) (2000/7)*coeff_bid;
		}
		else if(bids_count == 1) {
			return (long) (3000/7)*coeff_bid;
		}
		
		
		double bid;
		//finding the cost that the current task cause (cost of the plan with the task less the cost of a plan without)
		long diff = cost_agent - cost_agent_previous; 
		
        //determine our bids
		if(diff <= 0) { bid = averageProfit ;}
		else { bid  = diff + averageProfit - 100   ;}
		
		
		//Trying to detect if the enemy is influenced by large bids
		//Based on the possible cost they have (computed with our algorithm) we determine if there big increase in a wrong way
		//In other words, if they increase their bids a lot (more than two times)
		//the fact that we compute with our algorithm doesn't matter because we just evaluate the difference, 
		//even if they have a better algorithm, the ratio doesn't change
		double diff_enemy = cost_enemy-cost_enemy_previous+1;
		if(bids_count==2) 
			avg_bids_enemy = (long) (result_list_enemy.get(result_list_enemy.size()-1).get_bids()/(diff_enemy));
		
		if(bids_count==4) {
			if(avg_bids_enemy*2 < result_list_enemy.get(result_list_enemy.size()-1).get_bids()/(diff_enemy));{
				enemy_influenced = true;
				System.out.println("Enemy was influenced!");
			}
		}
		
		//if we detected that the enemy is influenced by large bids, we bet something realy big to increase our
		//profit for the next tasks
		if(enemy_influenced) {
			if(bids_count==11) return (long) (20000/7)*coeff_bid;
			if(bids_count> 11 && bids_count < 14) return (long) (diff + averageProfit*2);
		}
			
		//Decreasing bids if enemy too competitive (if we got less than tasks number / 2)
		if(!enemy_influenced && bids_count>8) {
			if(task_list_agent.size()<bids_count/2-1) averageProfit=averageProfit*0.9;
		}
		
		//As we calculate diff as: (cost with the new task) less (cost without it), for the first task we will bet 
		//a large number that is not representative of the real cost (because we will get other tasks which will allow us
		//to compute a plan that minimize the cost.
		if(task_list_agent.size()==1) return (long) (diff - averageProfit);

		
		//System.out.println("New cost agent : "+cost_agent +"New cost enemy : "+cost_enemy +" | diff agent: " +diff + " | bid: "+bid+ " | SUM: "+profit_agent);
		
		return (long) bid;
	}

	
	public long fast_plan(List<Vehicle> vehicles_list,  List<Task> task_list) {
		
		
        //INITIALIZATION
        cost = 9999999;
        
        while (bestPlans.size() < vehicles_list.size()) {
        	bestPlans.add(Plan.EMPTY);
        }
        
        
        
       
        //Creat a List of task to work with indexes
        List<Task> taskList = task_list;
        
        
        listAct.clear();
        
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
        for(int j = 0; j < vehicles_list.size() ; j++) {
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
        int j = 0;
    	while(n < listAct.size() ) {
    	
    		while(listAct.get(n).get_task().weight > vehicles_list.get(j).capacity()) 
    		{
    			
        		
        		if(j >= vehicles_list.size() - 1 ) {j = 0;}
        		else {j = j + 1;}
        		
        		
        		        		
    		}
    		
    		List<Act> act = nextTask.get(j);
			act.add(listAct.get(n));
    		act.add(listAct.get(n+1));
    		nextTask.put(j, act);
    		n =  n + 2;
    		if(j >= vehicles_list.size() - 1 ) {j = 0;}
    		else {j = j + 1;}
    		if(n == listAct.size() ) {break;}
    		
    	}
    	
    	
    	
    	
        //INITIALIZATION of nextTask_clone
    	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
    	
    	
    	
        	
		
    	
		List<Plan> plans = new ArrayList<Plan>();
		
        
		for(int m =0; m < numb_plan_computed; m++)
		{
        
		plans.clear();	
		
		for(int i = 0 ; i< vehicles_list.size(); i++)
        {
			Plan planVehicle= hashToPlan(vehicles_list.get(i), nextTask.get(i));
			plans.add(planVehicle);
			
        }		
        
                
		
        // Plans' cost for all vehicles
        while (plans.size() < vehicles_list.size()) {
            plans.add(Plan.EMPTY);
        }
        
        nextcost = 0;
        
        for(int i = 0 ; i< plans.size(); i++)
        {
        	nextcost = nextcost + plans.get(i).totalDistance()*vehicles_list.get(i).costPerKm();
        	
        }
        
        

        
        //OPIMIZATION = finding a better neigbour , if can't find after a number of iteration restart from initial solution
        nextTask = optimize( vehicles_list, taskList,plans,true);
        
        
		}

		long re = (long) cost;
		return re  ;
		
	}
	
	
	
	
	
	
	
	@Override
	public List<Plan> plan(List<Vehicle> vehicles, TaskSet tasks) {
        long time_start = System.currentTimeMillis();
        
        
        //INITIALIZATION
        cost = 9999999;
        bestcost = 9999999;
        while (bestPlans.size() < vehicles.size()) {
        	bestPlans.add(Plan.EMPTY);
        }
        while (ultraPlans.size() < vehicles.size()) {
        	ultraPlans.add(Plan.EMPTY);
        }
        
        
        
        listAct.clear();
        
        
       
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
        int j = 0;
    	while(n < listAct.size() ) {
    	
    		while(listAct.get(n).get_task().weight > vehicles.get(j).capacity()) 
    		{
    			
        		
        		if(j >= vehicles.size() - 1 ) {j = 0;}
        		else {j = j + 1;}
        		
        		
        		        		
    		}
    		
    		List<Act> act = nextTask.get(j);
			act.add(listAct.get(n));
    		act.add(listAct.get(n+1));
    		nextTask.put(j, act);
    		n =  n + 2;
    		if(j >= vehicles.size() - 1 ) {j = 0;}
    		else {j = j + 1;}
    		if(n == listAct.size() ) {break;}
    		
    	}
    	
    	
    	
    	
        //INITIALIZATION of nextTask_clone
    	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
    	
    	
    	
        	
		
		
		List<Plan> plans = new ArrayList<Plan>();
		long time_end = System.currentTimeMillis();
        long duration = time_end - time_start;
        
		while(duration+100 < timeout_plan) {
			
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
        	nextcost = nextcost + plans.get(i).totalDistance()*vehicles.get(i).costPerKm();
        	
        }
        
        
        
        
        
        
        
        
        
        //OPIMIZATION = finding a better neigbour , if can't find after a number of iteration restart from initial solution
        nextTask = optimize( vehicles, taskList,plans,false);
        
		
		
        
		
        time_end = System.currentTimeMillis();
        duration = time_end - time_start;
        //System.out.println("The plan was generated in "+duration+" milliseconds. COST BEST_PLAN = "+bestcost +" COST Actual Plan = "+cost + "   Cost neighbour = "+ nextcost );
		
        
        
		}
		List<Plan> re;
		//return the bestPLAN with the shortest distance
		if(bestcost  > cost ) {
			re= bestPlans;
			bestcost = cost;
		}
		else {re= ultraPlans;}
		
		System.out.println("FINAL PLAN COST: "+ bestcost );
		System.out.println("Tasks numer: " + task_list_agent.size());
		
        return re;
		
        
        
        
        
        
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
    		
    		
    		}
    		
    		
    	}
    	
    	
    	
    	for(int v =0; v < vehicles.size() ; v++) // for all vehicle
    	{
    		List<Act> actions = nextTask_clone.get(v);
    		int weight = 0;
    		for(int i = 0; i< actions.size() ; i++ ) { // for all possible actions
    			Act action1 = actions.get(i);
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
    
    
    
    
    private Hashtable<Integer, List<Act>>  optimize(List<Vehicle> vehicles, List<Task> taskList , List<Plan> plans, boolean fast ){
    	
		//Do a random transformation
		//If it is a solution , 
    	//compute plan,
    	//get cost
    	//if cost is lower keep the plan.
    	
    	
    	if(fast) {
    		if (nextcost < cost ) {
            	
            	// if cost is better then we update the nextTask
    	    	
    	    	nextTask = (Hashtable<Integer, List<Act>>) nextTask_clone.clone(); // make nextTask the new checkpoint 
    	    	cost = nextcost;
    	    	Collections.copy(bestPlans, plans);
    	    	
            }
            else { 
            	
            	
            	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone(); // load the last checkpoint
            	
	
            }
    		
    		
    	}
    	else {
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
        	restart(vehicles); 
        	
        	number_iter = 0;
        	
        }
    	}
        
       
    	
        // pick a random transformation
        Random rand = new Random();
      //here do transformation Next_clone = transf(next)
    	do {
    		
    		if(rand.nextBoolean()) { //give action
    			
    			int v1 = rand.nextInt(vehicles.size());
    			int v2 = rand.nextInt(vehicles.size());
    			while(v1 == v2 || nextTask_clone.get(v1).size() < 2  ) {
    				v1 = rand.nextInt(vehicles.size() );
    				v2 = rand.nextInt(vehicles.size());}// assure vehicle different
    			
    			int t = rand.nextInt(nextTask_clone.get(v1).size()); // pick random action in choosen vehicle
    			Task task = nextTask_clone.get(v1).get(t).get_task(); // get task
    			give(task,v1,v2);
    		}
			else {	//swap action
				
				int v1 = rand.nextInt(vehicles.size());
				int a1 = 0;
				int a2 = 0;
				if(nextTask_clone.get(v1).size() > 2 ) {
					
					
    			while(a1 == a2) {   a1 = rand.nextInt(nextTask_clone.get(v1).size());
								    a2 = rand.nextInt(nextTask_clone.get(v1).size());
								    }  // assure action in the v is diff
    			
    			Act action1 = nextTask_clone.get(v1).get(a1);
    			Act action2 = nextTask_clone.get(v1).get(a2);
    			swap(action1,action2,v1);
				}
    			
    			
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
	int k = 0;
	cost = 9999999;
	nextTask.clear();
	for(int j = 0; j < vehicles.size() ; j++) {
    	List<Act> act = new ArrayList<Act>();
       
    	nextTask.put(j, act);
    }
	 
    
	while(n < listAct.size() ) {
	
		while(listAct.get(n).get_task().weight > vehicles.get(k).capacity()) 
		{
			
    		
    		if(k >= vehicles.size() - 1 ) {k = 0;}
    		else {k = k + 1;}
    		
    		
    		        		
		}
		
		List<Act> act = nextTask.get(k);
		act.add(listAct.get(n));
		act.add(listAct.get(n+1));
		nextTask.put(k, act);
		n =  n + 2;
		if(k >= vehicles.size() - 1 ) {k = 0;}
		else {k = k + 1;}
		if(n == listAct.size() ) {break;}
		
	}
	
	
	nextTask_clone = (Hashtable<Integer, List<Act>>) nextTask.clone();
}    

// not used ( restart from a random solution )
private void shuffle(List<Vehicle> vehicles) {
	System.out.println("RESHUFFLE");
	Random rand = new Random();
	int n = 0; 
	cost = 50000;
	int j = 0;
	List<Act> act = nextTask.get(j);
	while(n < listAct.size())  {
		while(vehicles.get(j).capacity() < get_weight(vehicles, j, act) )  {
			System.out.println("cap : " +vehicles.get(j).capacity() +"Weight : "+ get_weight(vehicles, j, act));
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
//result class
class Result{
	private Task task;
	private int winner;
	private Long bids;
	
	public Result(Task task, int winner, Long bids) 
	{		
		this.winner = winner;
		this.bids = bids;
		this.task = task;		
	}
	public String toString() {
		return "Tasks: " + this.get_task() + ", winner= " + this.get_winner() + ", bids= " + String.format("%d", this.get_bids());
	}
	public int get_winner() {
		return this.winner;
	}
	public Task get_task() {
		return this.task;
	}
	public Long get_bids() {
		return this.bids;
	}	
	 
}
