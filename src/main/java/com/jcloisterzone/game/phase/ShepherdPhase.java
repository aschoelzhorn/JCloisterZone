package com.jcloisterzone.game.phase;

import com.jcloisterzone.PointCategory;
import com.jcloisterzone.action.FlockAction;
import com.jcloisterzone.board.pointer.FeaturePointer;
import com.jcloisterzone.event.play.PlayEvent.PlayEventMeta;
import com.jcloisterzone.event.play.ScoreEvent;
import com.jcloisterzone.event.play.TokenPlacedEvent;
import com.jcloisterzone.feature.Farm;
import com.jcloisterzone.figure.Meeple;
import com.jcloisterzone.figure.Shepherd;
import com.jcloisterzone.game.RandomGenerator;
import com.jcloisterzone.game.capability.SheepCapability;
import com.jcloisterzone.game.capability.SheepCapability.SheepToken;
import com.jcloisterzone.game.state.ActionsState;
import com.jcloisterzone.game.state.GameState;
import com.jcloisterzone.game.state.PlacedTile;
import com.jcloisterzone.reducers.AddPoints;
import com.jcloisterzone.reducers.UndeployMeeple;
import com.jcloisterzone.wsio.message.FlockMessage;
import com.jcloisterzone.wsio.message.FlockMessage.FlockOption;

import io.vavr.Predicates;
import io.vavr.Tuple2;
import io.vavr.collection.List;
import io.vavr.collection.Map;
import io.vavr.collection.Seq;
import io.vavr.collection.Vector;


@RequiredCapability(SheepCapability.class)
public class ShepherdPhase extends Phase {


	public ShepherdPhase(RandomGenerator random) {
		super(random);
	}

	@Override
	public StepResult enter(GameState state) {
		PlacedTile lastPlaced = state.getLastPlaced();
		GameState _state = state;
		Seq<Farm> closedFarmsWithShepherd = state.getDeployedMeeples()
				.filterKeys(Predicates.instanceOf(Shepherd.class))
				.values()
				.map(fp -> (Farm) _state.getFeature(fp))
				.distinct()
				.filter(farm -> !farm.isOpen(_state));

		Shepherd shepherd = (Shepherd) state.getTurnPlayer().getSpecialMeeples(state).find(m -> m instanceof Shepherd).getOrNull();
		FeaturePointer shepherdFp = shepherd.getDeployment(state);

		boolean isFarmExtended = false;
		boolean alreadyExpanded = false;

		if (shepherdFp != null) {
			boolean isJustPlaced = lastPlaced.getPosition().equals(shepherdFp.getPosition());

			Farm farm = (Farm) state.getFeature(shepherdFp);
			isFarmExtended = state.getTileFeatures2(lastPlaced.getPosition()).map(Tuple2::_2).contains(farm);

			// close farms after placed flock is expanded http://wikicarpedia.com/index.php/Hills_%26_Sheep#cite_note-2
			if (isJustPlaced || closedFarmsWithShepherd.contains(farm)) {
				state = expandFlock(state, shepherdFp);
				alreadyExpanded = true;
			}
		}

		for (Farm farm : closedFarmsWithShepherd) {
			state = scoreFlock(state, farm);
		}

		if (shepherdFp == null || !isFarmExtended || alreadyExpanded) {
			return next(state);
		}

		FlockAction action = new FlockAction();
        ActionsState as = new ActionsState(state.getTurnPlayer(), action, false);
        return promote(state.setPlayerActions(as));
	}

	@PhaseMessageHandler
    public StepResult handleFlockMessage(GameState state, FlockMessage msg) {
		Shepherd shepherd = (Shepherd) state.getTurnPlayer().getSpecialMeeples(state).find(m -> m instanceof Shepherd).getOrNull();
		FeaturePointer shepherdFp = shepherd.getDeployment(state);

		if (msg.getValue() == FlockOption.EXPAND) {
			return next(expandFlock(state, shepherdFp));
		} else {
			return scoreFlock(state, shepherdFp);
		}
    }

	private Map<Meeple, FeaturePointer> getShepherdsOnFarm(GameState state, Farm farm) {
		return state.getDeployedMeeples().filter((m, fp) -> m instanceof Shepherd && farm.getPlaces().contains(fp));
	}

	private StepResult scoreFlock(GameState state, FeaturePointer shepherdFp) {
		Farm farm = (Farm) state.getFeature(shepherdFp);
		state = scoreFlock(state, farm);
		state = clearActions(state);
		return next(state);
	}

	private GameState scoreFlock(GameState state, Farm farm) {
		SheepCapability cap = state.getCapabilities().get(SheepCapability.class);
		Map<FeaturePointer, List<SheepToken>> placedTokens = cap.getModel(state);
		Map<Meeple, FeaturePointer> shepherdsOnFarm = getShepherdsOnFarm(state, farm);
		int points = shepherdsOnFarm.values().map(fp -> {
			return placedTokens.get(fp).get().map(SheepToken::sheepCount).sum();
		}).sum().intValue();

		for (Tuple2<Meeple, FeaturePointer> t : shepherdsOnFarm) {
		    Shepherd m = (Shepherd) t._1;
		    state = (new AddPoints(m.getPlayer(), points, PointCategory.SHEEP)).apply(state);
			ScoreEvent scoreEvent = new ScoreEvent(points, PointCategory.SHEEP, false, t._2, m);
            state = state.appendEvent(scoreEvent);
            state = (new UndeployMeeple(m, false)).apply(state);
		}

		return cap.setModel(
			state,
			shepherdsOnFarm.values().foldLeft(placedTokens, (acc, fp) -> acc.remove(fp))
		);
	}

	private GameState expandFlock(GameState state, FeaturePointer shepherdFp) {
		SheepToken drawnToken = drawTokenFromBag(state);

		state = state.appendEvent(new TokenPlacedEvent(PlayEventMeta.createWithoutPlayer(), drawnToken, shepherdFp));

		SheepCapability cap = state.getCapabilities().get(SheepCapability.class);

		if (drawnToken == SheepToken.WOLF) {
			Farm farm = (Farm) state.getFeature(shepherdFp);
			Map<Meeple, FeaturePointer> shepherdsOnFarm = getShepherdsOnFarm(state, farm);
			for (Meeple m : shepherdsOnFarm.keySet()) {
				state = (new UndeployMeeple(m, true)).apply(state);
			}

			// remove all placed tokens from capability model
			state = cap.updateModel(state, placedTokens ->
				shepherdsOnFarm.values().foldLeft(placedTokens, (acc, fp) -> acc.remove(fp))
			);

			return state;
		}

		return state.getCapabilities().get(SheepCapability.class).updateModel(state, placedTokens -> {
			return placedTokens.put(shepherdFp, List.of(drawnToken), (l1, l2) -> l1.appendAll(l2));
		});
	}

	private SheepToken drawTokenFromBag(GameState state) {
		Vector<SheepToken> bag = state.getCapabilities().get(SheepCapability.class).getBagConent(state);
		return bag.get(getRandom().nextInt(bag.size()));
	}

}
