(function () {
    // long task
    function _slowTask() {
        console.log("Blocking task started");
        let counter = 10000000;
        while (counter-- > 0) {
            if (counter % 1000 === 0) {
                console.log("Blocking Task: still doing something useless...", counter);
            }
        }
        console.log("Blocking Task completed");
    }
    setTimeout(_slowTask,10);

    Promise.reject(new Error("Rejected promise"));
    throw new Error("A Javascript error");
})();
