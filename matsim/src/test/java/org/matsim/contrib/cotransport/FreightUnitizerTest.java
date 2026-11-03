package org.matsim.contrib.cotransport;

import org.junit.jupiter.api.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import static org.junit.jupiter.api.Assertions.*;

class FreightUnitizerTest {
	@Test
	void javaAndOfflineBuilderShareTheSameGoldenContract() throws Exception {
		try (var stream = getClass().getResourceAsStream("freight_unitizer_contract.csv")) {
			assertNotNull(stream);
			try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				reader.readLine();
				for (String line = reader.readLine(); line != null; line = reader.readLine()) {
					String[] row = line.split(",", -1);
					var operation = (java.util.function.IntSupplier) () -> FreightUnitizer.proportional(
							Double.parseDouble(row[1]), "_cap_").capacityUnits(Double.parseDouble(row[2]),
							row[3].isBlank() ? null : Integer.parseInt(row[3]));
					if (row[4].equals("ERROR")) assertThrows(IllegalArgumentException.class, operation::getAsInt, row[0]);
					else assertEquals(Integer.parseInt(row[4]), operation.getAsInt(), row[0]);
				}
			}
		}
	}

	@Test
	void unitIdsAreImmutableAndStableAcrossDefaultLocales() {
		FreightUnitizer unitizer = FreightUnitizer.proportional(5, "_cap_");
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(Locale.forLanguageTag("ar"));
			List<String> ids = unitizer.unitIds("parcel", 100);
			assertEquals("parcel", ids.getFirst());
			assertEquals("parcel_cap_02", ids.get(1));
			assertEquals("parcel_cap_100", ids.getLast());
			assertEquals(100, ids.stream().distinct().count());
			assertThrows(UnsupportedOperationException.class, () -> ids.add("extra"));
		} finally {
			Locale.setDefault(original);
		}
	}

	@Test
	void rejectsBlankIdsTokensAndInvalidCounts() {
		assertThrows(IllegalArgumentException.class, () -> FreightUnitizer.proportional(5, ""));
		FreightUnitizer unitizer = FreightUnitizer.proportional(5, "_cap_");
		assertThrows(IllegalArgumentException.class, () -> unitizer.unitIds(" ", 1));
		assertThrows(IllegalArgumentException.class, () -> unitizer.unitIds("order", 0));
		assertThrows(IllegalArgumentException.class, () -> unitizer.unitIds("order", -1));
	}
}
