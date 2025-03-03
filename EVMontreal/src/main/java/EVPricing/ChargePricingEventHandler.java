package EVPricing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.LinkLeaveEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.PersonMoneyEvent;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.VehicleEntersTrafficEvent;
import org.matsim.api.core.v01.events.VehicleLeavesTrafficEvent;
import org.matsim.api.core.v01.events.handler.LinkLeaveEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleEntersTrafficEventHandler;
import org.matsim.api.core.v01.events.handler.VehicleLeavesTrafficEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.contrib.ev.charging.ChargingEndEvent;
import org.matsim.contrib.ev.charging.ChargingEndEventHandler;
import org.matsim.contrib.ev.charging.ChargingStartEvent;
import org.matsim.contrib.ev.charging.ChargingStartEventHandler;
import org.matsim.contrib.ev.fleet.ElectricFleet;
import org.matsim.contrib.ev.fleet.ElectricVehicle;
import org.matsim.contrib.ev.infrastructure.Charger;
import org.matsim.contrib.ev.infrastructure.ChargingInfrastructureSpecification;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.controler.MatsimServices;
import org.matsim.core.events.MobsimScopeEventHandler;
import org.matsim.vehicles.Vehicle;
import org.matsim.vehicles.VehicleUtils;

import com.google.inject.Inject;

/**
 * 
 * @author ashrafzaman
 *
 *The class assumes that the owner of the EV pays for the charging. There is no distance based money cost for car
 *The gasoline cost is distributed among users based on distance. 
 *
 *For the EV, a car passenger will not pay any money for the distance traveled. Only the owner of the car will pay.
 *
 */

public class ChargePricingEventHandler implements ChargingStartEventHandler, ChargingEndEventHandler, LinkLeaveEventHandler,TransitDriverStartsEventHandler,
PersonEntersVehicleEventHandler, PersonLeavesVehicleEventHandler,VehicleEntersTrafficEventHandler,VehicleLeavesTrafficEventHandler, MobsimScopeEventHandler{
	

	private ChargingInfrastructureSpecification chargingInfrastructure;
	
	@Inject
	private ChargerPricingProfiles pricingProfies;
	
	private ElectricFleet fleet;
	
	private Set<Id<Vehicle>> transitVehicles = new HashSet<>();
	private Scenario scenario;
	
	public final String ChargingCostName = "EV charging";
	public final String gasMoneyString = "Gas Money";
	
	private EventsManager events;
	
	public final Map<Id<ElectricVehicle>,Id<Person>> personIdForEV;
	
	public Map<Id<Vehicle>,Set<Id<Person>>> vehicleToPersonMapping = new HashMap<>(); 
	public Map<Id<Vehicle>,Double> vehicleToDistance = new HashMap<>();
	
	public double gasMoneyCostPer_m = 2.0E-4;
	
	private Map<String,chargingDetails> personLists = new HashMap<>();// the price has to be per kW
	private Map<String,Double> price = new HashMap<>();//Charger type to price 
	//private Map<String,Set<Id<Person>>> vehicleToPersonMapping = new HashMap<>();
	
	@Inject
	ChargePricingEventHandler(final MatsimServices controler,ChargingInfrastructureSpecification chargingInfrastructure, ElectricFleet data) {
		this.scenario = controler.getScenario();
		events = controler.getEvents();
		this.fleet = data;
		this.chargingInfrastructure = chargingInfrastructure;
		price.put("Level 1", 0.28);
		price.put("Level 2", 0.58);
		price.put("Fast", 0.78);
		personIdForEV = new HashMap<>();
		controler.getScenario().getPopulation().getPersons().values().forEach(p->{
			VehicleUtils.getVehicleIds(p).values().forEach(v->{
				Id<ElectricVehicle> eId = Id.create(v.toString(), ElectricVehicle.class);
				if(fleet.getElectricVehicles().keySet().contains(eId)) {
					this.personIdForEV.put(eId, p.getId());
				}
			});
		});
	}
	
	@Override
	public void reset(int iteration) {
		this.vehicleToPersonMapping.clear();
		this.vehicleToDistance.clear();
		this.personLists.clear();
	}

	@Override
	public void handleEvent(ChargingEndEvent event) {
		
		Id<Person> pId = this.personIdForEV.get(event.getVehicleId());
		chargingDetails cd = this.personLists.get(pId.toString());
		String chargerType = this.chargingInfrastructure.getChargerSpecifications().get(cd.charger).getChargerType();
		double pricePerkWhr = this.price.get(chargerType);
		Double juleCharged= cd.v.getBattery().getSoc()-cd.initialSoc;//warning: unit Conversion
		Double cost = pricePerkWhr*juleCharged/3600000;
		this.events.processEvent(new PersonMoneyEvent(event.getTime(), pId, cost*-1, this.ChargingCostName, cd.charger.toString()+"___"+chargerType));
		this.personLists.remove(pId.toString());
		}

	/**
	 * the personId is assumed same as vehicleId
	 */
	@Override
	public void handleEvent(ChargingStartEvent event) {
		personLists.put(event.getVehicleId().toString(), new chargingDetails(event.getChargerId(),event.getTime(),this.fleet.getElectricVehicles().get(event.getVehicleId())));		
	}

	@Override
	public void handleEvent(LinkLeaveEvent event) {
		if(!this.fleet.getElectricVehicles().containsKey(Id.create(event.getVehicleId().toString(),ElectricVehicle.class)) && !this.transitVehicles.contains(event.getVehicleId())){
			double d = this.scenario.getNetwork().getLinks().get(event.getLinkId()).getLength();
			this.vehicleToDistance.compute(event.getVehicleId(), (k,v)->v+d);
		}
	}

	@Override
	public void handleEvent(PersonEntersVehicleEvent event) {
		if(!this.transitVehicles.contains(event.getVehicleId())) {
			if(!this.vehicleToPersonMapping.containsKey(event.getVehicleId()))this.vehicleToPersonMapping.put(event.getVehicleId(), new HashSet<>());
			if(!this.fleet.getElectricVehicles().keySet().contains(Id.create(event.getVehicleId().toString(),ElectricVehicle.class)))this.chargePeopleForGasoline(event.getVehicleId(), event.getTime());
			this.vehicleToPersonMapping.get(event.getVehicleId()).add(event.getPersonId());
			
			// if the vehicle did not have anyone
		}
		
		
	}

	@Override
	public void handleEvent(PersonLeavesVehicleEvent event) {
		// TODO Auto-generated method stub
		if(!this.transitVehicles.contains(event.getVehicleId())) {
			if(!this.fleet.getElectricVehicles().keySet().contains(Id.create(event.getVehicleId().toString(),ElectricVehicle.class))
					&& this.vehicleToDistance.containsKey(event.getVehicleId()))this.chargePeopleForGasoline(event.getVehicleId(), event.getTime());
			this.vehicleToPersonMapping.get(event.getVehicleId()).remove(event.getPersonId());
		}
	}
	
	

	void chargePeopleForGasoline(Id<Vehicle> vId, double time) {
		if(!this.vehicleToPersonMapping.get(vId).isEmpty()){
			double distance = this.vehicleToDistance.get(vId);
			double cost = this.gasMoneyCostPer_m*distance;
			double indCost = cost/this.vehicleToPersonMapping.get(vId).size();
			for(Id<Person> pId :this.vehicleToPersonMapping.get(vId)) {
				this.events.processEvent(new PersonMoneyEvent(time, pId, indCost, this.gasMoneyString,  vId.toString()));
			}
			
		}
	}

	@Override
	public void handleEvent(VehicleLeavesTrafficEvent event) {
		this.vehicleToDistance.remove(event.getVehicleId());
	}

	@Override
	public void handleEvent(VehicleEntersTrafficEvent event) {
		this.vehicleToDistance.put(event.getVehicleId(), 0.);
		
	}

	@Override
	public void handleEvent(TransitDriverStartsEvent event) {
		transitVehicles.add(event.getVehicleId());
	}
	@Override
	public void cleanupAfterMobsim(int iteration) {
		
	}
	
//	void tutorial(Id<Charger> cId) {
//		this.pricingProfies.getChargerPricingProfiles().get(cId).getPricingProfile().get(cId)
//	}
	
	// to do here
}

class chargingDetails{

	public final Id<Charger> charger;
	public final double startingTime;
	public double endingTime;
	public ElectricVehicle v;
	public double initialSoc = 0;
	
	public chargingDetails(Id<Charger> chargerId, double startingTime, ElectricVehicle v) {
		
		this.charger = chargerId;
		this.startingTime =startingTime;
		this.v = v;
		this.initialSoc = v.getBattery().getSoc();
	}
}
