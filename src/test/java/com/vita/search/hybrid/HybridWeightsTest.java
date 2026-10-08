package com.vita.search.hybrid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HybridWeightsTest {

	@Test
	void acceptsAOneSidedWeightForTheVectorOnlyRegressionCheck() {
		HybridWeights vectorOnly = new HybridWeights(1.0, 0.0, 60);

		assertThat(vectorOnly.keyword()).isZero();
	}

	@Test
	void rejectsNegativeWeights() {
		assertThatThrownBy(() -> new HybridWeights(-1.0, 1.0, 60)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new HybridWeights(1.0, -0.1, 60)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsWeightsThatAreBothZero() {
		assertThatThrownBy(() -> new HybridWeights(0.0, 0.0, 60))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("둘 다 0");
	}

	@Test
	void rejectsANonPositiveRrfK() {
		assertThatThrownBy(() -> new HybridWeights(1.0, 1.0, 0)).isInstanceOf(IllegalArgumentException.class);
	}
}
