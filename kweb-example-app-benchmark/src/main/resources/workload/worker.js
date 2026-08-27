self.onmessage = ({ data }) => {
  const values = data.values;
  let checksum = 2166136261;
  for (let round = 0; round < data.rounds; round += 1) {
    for (let index = 0; index < values.length; index += 1) {
      checksum ^= values[index] + round;
      checksum = Math.imul(checksum, 16777619) >>> 0;
    }
  }
  self.postMessage({ checksum, count: values.length, rounds: data.rounds });
};
