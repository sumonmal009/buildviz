var graphDescription = function (d3) {
    var module = {};

    var asUnsortedList = function (textList) {
        var ul = d3.select(document.createElement('ul'));
        textList.forEach(function (text) {
            ul.append('li').text(text);
        });
        return ul.node();
    };

    var createBlock = function (text, headline) {
        var block = d3.select(document.createElement('div'));
        if (headline) {
            block
                .append('h4')
                .text(headline);
        }
        if (typeof text === typeof '') {
            block
                .append('p')
                .text(text);
        } else {
            block.node().appendChild(asUnsortedList(text));
        }
        return block.node();
    };

    var createLink = function (text, link) {
        var h4 = d3.select(document.createElement('h4'));
        h4.append('a')
            .attr('href', link)
            .text(text);
        return h4.node();
    };

    module.create = function (params) {
        var container = d3.select(document.createElement('div'))
                .attr('class', 'graphDescription')
                .text('?'),
            section = container
                .append('section');

        [{text: params.description},
         {headline: 'Answer to', text: params.answer},
         {headline: 'Legend', text: params.legend}].forEach(function (block) {
             if (block.text) {
                 section.node().appendChild(createBlock(block.text, block.headline));
             }
         });

        if (params.csvSource) {
            section.node().appendChild(createLink('Source as CSV', params.csvSource));
        }

        return {
            widget: container.node()
        };
    };

    return module;
}(d3);